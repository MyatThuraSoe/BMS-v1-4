package com.bms.service;

import com.bms.dto.order.OrderCancelRequest;
import com.bms.dto.order.OrderConvertRequest;
import com.bms.dto.order.OrderCreateRequest;
import com.bms.dto.order.OrderItemResponse;
import com.bms.dto.order.OrderResponse;
import com.bms.entity.Customer;
import com.bms.entity.Order;
import com.bms.entity.OrderItem;
import com.bms.entity.Product;
import com.bms.entity.Promotion;
import com.bms.entity.Role;
import com.bms.entity.ShopInfo;
import com.bms.entity.SystemSetting;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.CustomerRepository;
import com.bms.repository.OrderRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.ShopInfoRepository;
import com.bms.repository.SystemSettingRepository;
import com.bms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    @Autowired
    private ShopTimeService shopTimeService;

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;
    private final UserRepository userRepository;
    private final ShopInfoRepository shopInfoRepository;
    private final SequenceService sequenceService;
    private final SaleService saleService;
    private final AuditLogService auditLogService;

    @Autowired
    private PeriodGuard periodGuard;

    @Autowired
    private PromotionService promotionService;

    @Autowired
    private SystemSettingRepository systemSettingRepository;

    @Transactional(readOnly = true)
    public Page<OrderResponse> getOrders(String status, LocalDate startDate, LocalDate endDate, Long customerId, String orderNumber, Pageable pageable) {
        Order.OrderStatus orderStatus = null;
        if (status != null && !status.isBlank()) {
            try {
                orderStatus = Order.OrderStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new BusinessException("Invalid order status: " + status);
            }
        }

        LocalDateTime start = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime end = endDate != null ? endDate.plusDays(1).atStartOfDay() : null;
        String search = (orderNumber != null && !orderNumber.isBlank()) ? orderNumber.trim() : null;

        return orderRepository.findFilteredOrders(orderStatus, start, end, customerId, search, pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderById(Long id) {
        Order order = orderRepository.findByIdWithItems(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + id));
        if (!Boolean.TRUE.equals(order.getIsActive()) || order.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Order not found: " + id);
        }
        return toResponse(order);
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderResponse createOrder(OrderCreateRequest request, Long userId) {
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new BusinessException("Order must have at least one item");
        }

        String orderNumber = sequenceService.nextOrderNumber();

        Order order = new Order();
        order.setOrderNumber(orderNumber);
        order.setCashierId(userId);
        order.setNotes(request.getNotes());

        // Resolve customer (optional for pre-orders)
        if (request.getCustomerId() != null) {
            Customer customer = customerRepository.findById(request.getCustomerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + request.getCustomerId()));
            if (!Boolean.TRUE.equals(customer.getIsActive()) || customer.getDeletedAt() != null) {
                throw new ResourceNotFoundException("Customer not found: " + request.getCustomerId());
            }
            order.setCustomer(customer);
            order.setCustomerDisplayName(buildCustomerDisplayName(customer));
        } else {
            order.setCustomerDisplayName("Walk-in");
        }

        BigDecimal taxRate = getShopTaxRate();
        // O2: server-authoritative pricing, locked at creation and kept at conversion.
        LocalDateTime pricingMoment = shopTimeService.now();
        List<Promotion> activePromotions = promotionService.getActivePromotions(pricingMoment);
        BelowCostPolicy belowCostPolicy = getBelowCostPolicy();
        boolean belowCostAllowedForUser = belowCostPolicy != BelowCostPolicy.REQUIRE_MANAGER
                || isManagerOrAdmin(userId);
        BigDecimal subtotal = BigDecimal.ZERO;
        // O6: the order header keeps the same discount split a direct sale does,
        // so a converted order reports promotion value exactly like the walk-in.
        BigDecimal promotionDiscount = BigDecimal.ZERO;
        BigDecimal priceListDiscount = BigDecimal.ZERO;

        // Validate + reserve stock while building items
        // TASK-3: merge order lines by product before pricing, so each product
        // is priced once on its total quantity. Volume tiers are evaluated on the
        // full order quantity, not per line. TreeMap keeps lock order deterministic
        // (same as SaleService), avoiding deadlocks between concurrent orders.
        Map<Long, Integer> mergedQtyByProduct = new java.util.TreeMap<>();
        for (OrderCreateRequest.OrderItemRequest itemRequest : request.getItems()) {
            if (itemRequest.getProductId() == null || itemRequest.getQuantity() == null
                    || itemRequest.getQuantity() <= 0) {
                throw new BusinessException("Order item quantity must be positive");
            }
            mergedQtyByProduct.merge(itemRequest.getProductId(), itemRequest.getQuantity(), Integer::sum);
        }
        Map<Long, Integer> reservedByProduct = new java.util.TreeMap<>();
        for (Map.Entry<Long, Integer> entry : mergedQtyByProduct.entrySet()) {
            Product product = productRepository.findByIdForUpdate(entry.getKey())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + entry.getKey()));
            if (!Boolean.TRUE.equals(product.getIsActive()) || product.getDeletedAt() != null) {
                throw new ResourceNotFoundException("Product not found: " + entry.getKey());
            }
            int mergedQty = entry.getValue();
            if (product.getAvailableQuantity() < mergedQty) {
                throw new BusinessException("Insufficient available stock for product '" + product.getName()
                        + "'. Available: " + product.getAvailableQuantity() + ", Requested: " + mergedQty);
            }
            reservedByProduct.put(entry.getKey(), mergedQty);
        }

        // Price each merged product once on its total quantity.
        for (Map.Entry<Long, Integer> entry : mergedQtyByProduct.entrySet()) {
            Product product = productRepository.findByIdForUpdate(entry.getKey())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + entry.getKey()));
            int mergedQty = entry.getValue();

            PricingQuote quote = promotionService.calculateBestPrice(
                    product, mergedQty, order.getCustomer(), pricingMoment, activePromotions);
            BigDecimal effectiveUnitPrice = quote.getUnitPrice();
            BigDecimal lineCost = product.getCostPrice();
            if (lineCost != null && effectiveUnitPrice.compareTo(lineCost) < 0) {
                if (belowCostPolicy == BelowCostPolicy.BLOCK) {
                    throw new BusinessException("Product '" + product.getName()
                            + "' is priced below cost; below-cost sales are blocked");
                }
                if (belowCostPolicy == BelowCostPolicy.REQUIRE_MANAGER && !belowCostAllowedForUser) {
                    throw new BusinessException("Product '" + product.getName()
                            + "' is priced below cost; manager approval is required");
                }
            }

            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProduct(product);
            item.setQuantity(mergedQty);
            item.setUnitPrice(effectiveUnitPrice);
            item.setOriginalUnitPrice(quote.getOriginalUnitPrice());
            item.setLineDiscount(quote.getLineDiscount());
            item.setPromotionId(quote.getPromotionId());
            item.setPromotionName(quote.getPromotionName());
            item.setCostPriceAtOrder(product.getCostPrice());
            BigDecimal itemTotal = effectiveUnitPrice.multiply(BigDecimal.valueOf(mergedQty))
                    .setScale(2, RoundingMode.HALF_UP);
            item.setTotalPrice(itemTotal);

            order.getItems().add(item);
            subtotal = subtotal.add(itemTotal);
            if ("PROMOTION".equals(quote.getSource())) {
                promotionDiscount = promotionDiscount.add(quote.getLineDiscount());
            } else if ("PRICE_LIST".equals(quote.getSource())) {
                priceListDiscount = priceListDiscount.add(quote.getLineDiscount());
            }
        }

        BigDecimal discountAmount = computeShopDiscount(subtotal);
        order.setSubtotal(subtotal);
        order.setDiscountAmount(discountAmount);

        // Below-cost check at creation: allocate the header discount across lines
        // and compare the net unit price against cost, same as createSale does.
        BelowCostPolicy orderBelowCostPolicy = getBelowCostPolicy();
        boolean orderBelowCostAllowed = orderBelowCostPolicy != BelowCostPolicy.REQUIRE_MANAGER
                || isManagerOrAdmin(userId);
        Map<OrderItem, BigDecimal> discountAllocations = new java.util.HashMap<>();
        BigDecimal grossTotal = order.getItems().stream()
                .map(OrderItem::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal orderDiscount = order.getDiscountAmount() != null ? order.getDiscountAmount() : BigDecimal.ZERO;
        if (orderDiscount.signum() > 0 && grossTotal.signum() > 0) {
            BigDecimal allocated = BigDecimal.ZERO;
            OrderItem remainderTarget = null;
            for (OrderItem item : order.getItems()) {
                if (item.getTotalPrice().signum() > 0
                        && (remainderTarget == null || item.getId() == null
                        || remainderTarget.getId() == null || item.getId() > remainderTarget.getId())) {
                    remainderTarget = item;
                }
            }
            for (OrderItem item : order.getItems()) {
                if (item == remainderTarget) continue;
                BigDecimal share = orderDiscount.signum() > 0 && grossTotal.signum() > 0 && item.getTotalPrice().signum() > 0
                        ? orderDiscount.multiply(item.getTotalPrice()).divide(grossTotal, 2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                discountAllocations.put(item, share);
                allocated = allocated.add(share);
            }
            if (remainderTarget != null) {
                discountAllocations.put(remainderTarget, orderDiscount.subtract(allocated).max(BigDecimal.ZERO));
            }
        }
        for (OrderItem item : order.getItems()) {
            BigDecimal lineCost = item.getCostPriceAtOrder();
            BigDecimal lineNet = item.getTotalPrice()
                    .subtract(discountAllocations.getOrDefault(item, BigDecimal.ZERO));
            BigDecimal netUnit = lineNet.divide(BigDecimal.valueOf(item.getQuantity()), 4,
                    java.math.RoundingMode.HALF_UP);
            if (lineCost != null && netUnit.compareTo(lineCost) < 0) {
                if (orderBelowCostPolicy == BelowCostPolicy.BLOCK) {
                    throw new BusinessException("Product '" + item.getProduct().getName()
                            + "' is priced below cost at order creation; below-cost sales are blocked");
                }
                if (orderBelowCostPolicy == BelowCostPolicy.REQUIRE_MANAGER && !orderBelowCostAllowed) {
                    throw new BusinessException("Product '" + item.getProduct().getName()
                            + "' is priced below cost at order creation; manager approval is required");
                }
            }
        }

        order.setPromotionDiscount(promotionDiscount.setScale(2, RoundingMode.HALF_UP));
        order.setPriceListDiscount(priceListDiscount.setScale(2, RoundingMode.HALF_UP));
        // P5: tax applies to the discounted line value, using the same proportional
        // allocation rule as sales so conversion reproduces identical line taxes.
        BigDecimal taxAmount = applyDiscountedLineTaxes(order, taxRate);
        order.setTaxAmount(taxAmount);
        order.setTotalAmount(subtotal.subtract(discountAmount).add(taxAmount));

        Order saved = orderRepository.save(order);

        // Reserve stock only after a successful save. The map aggregates all lines,
        // so a product repeated across lines is checked against its TOTAL, preventing
        // over-reservation that per-line checks would miss.
        for (Map.Entry<Long, Integer> entry : reservedByProduct.entrySet()) {
            Product product = productRepository.findByIdForUpdate(entry.getKey())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + entry.getKey()));
            int requested = entry.getValue();
            if (product.getAvailableQuantity() < requested) {
                throw new BusinessException("Insufficient available stock for product '" + product.getName()
                        + "'. Available: " + product.getAvailableQuantity() + ", Requested: " + requested);
            }
            product.setReservedQuantity(product.getReservedQuantity() + requested);
            productRepository.save(product);
        }

        auditLogService.logAction(userId, "ORDER_CREATE",
                "Order created: " + orderNumber,
                "Order", saved.getId(), null, saved.toString());

        return toResponse(saved);
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderResponse convertOrder(Long id, OrderConvertRequest request, Long userId) {
        periodGuard.assertOpen(shopTimeService.now());
        Order order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + id));
        if (order.getStatus() != Order.OrderStatus.PENDING) {
            throw new BusinessException("Only pending orders can be converted to a sale");
        }

        String method = request.getPaymentMethod() != null ? request.getPaymentMethod().trim().toUpperCase() : "CASH";

        com.bms.dto.response.SaleResponse sale = saleService.createSaleFromOrder(
                order, method, request.getAmountPaid(), request.getDueDate(), userId);

        order.setStatus(Order.OrderStatus.CONVERTED);
        order.setConvertedSaleId(sale.getId());
        order.setConvertedAt(shopTimeService.now());
        Order saved = orderRepository.save(order);

        auditLogService.logAction(userId, "ORDER_CONVERT",
                "Order converted to sale: " + order.getOrderNumber() + " -> " + sale.getInvoiceNumber(),
                "Order", saved.getId(), null, saved.toString());

        return toResponse(saved);
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderResponse cancelOrder(Long id, OrderCancelRequest request, Long userId) {
        Order order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + id));
        if (order.getStatus() != Order.OrderStatus.PENDING) {
            throw new BusinessException("Only pending orders can be cancelled");
        }

        for (OrderItem item : order.getItems()) {
            Product product = productRepository.findByIdForUpdate(item.getProduct().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + item.getProduct().getId()));
            int released = Math.min(product.getReservedQuantity(), item.getQuantity());
            product.setReservedQuantity(product.getReservedQuantity() - released);
            productRepository.save(product);
        }

        String oldValues = order.toString();
        order.setStatus(Order.OrderStatus.CANCELLED);
        order.setCancelledAt(shopTimeService.now());
        order.setCancelledBy(userId);
        order.setCancelReason(request != null ? request.getReason() : null);
        Order saved = orderRepository.save(order);

        auditLogService.logAction(userId, "ORDER_CANCEL",
                "Order cancelled: " + order.getOrderNumber()
                        + (request != null && request.getReason() != null ? ". Reason: " + request.getReason() : ""),
                "Order", saved.getId(), oldValues, saved.toString());

        return toResponse(saved);
    }

    // ------------------------------------------------------------------
    // Scheduled reservation expiry (O8)
    // ------------------------------------------------------------------

    /** O8: the sweep runs with no cashier behind it, so its audit rows are system-authored. */
    static final Long SYSTEM_USER = 0L;

    /**
     * Frees the stock a pre-order is holding after the shop's reservation window.
     * Off unless {@code shop.order.reservationHours} is above zero.
     */
    @Scheduled(cron = "0 15 * * * *")
    @Transactional(rollbackFor = Exception.class)
    public int expireStaleReservations() {
        int hours = reservationExpiryHours();
        if (hours <= 0) {
            return 0;
        }
        List<Order> expired = orderRepository.findPendingOrdersOlderThan(
                shopTimeService.now().minusHours(hours));
        for (Order order : expired) {
            OrderCancelRequest request = new OrderCancelRequest();
            request.setReason("Reservation expired");
            cancelOrder(order.getId(), request, SYSTEM_USER);
        }
        return expired.size();
    }

    /** O8: how long a pending order may reserve stock; unusable values mean off. */
    private int reservationExpiryHours() {
        String raw = systemSettingRepository.findBySettingKey("shop.order.reservationHours")
                .map(SystemSetting::getSettingValue).orElse(null);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private OrderResponse toResponse(Order order) {
        OrderResponse response = new OrderResponse();
        response.setId(order.getId());
        response.setOrderNumber(order.getOrderNumber());
        response.setCustomerId(order.getCustomer() != null ? order.getCustomer().getId() : null);
        response.setCustomerName(order.getCustomerDisplayName());
        response.setCashierId(order.getCashierId());
        userRepository.findById(order.getCashierId()).ifPresent(cashier ->
            response.setCashierName(cashier.getFirstName() + " " + cashier.getLastName())
        );
        response.setCreatedAt(order.getCreatedAt());
        response.setSubtotal(order.getSubtotal());
        response.setDiscountAmount(order.getDiscountAmount() != null ? order.getDiscountAmount() : BigDecimal.ZERO);
        response.setTaxAmount(order.getTaxAmount());
        response.setTotalAmount(order.getTotalAmount());
        response.setStatus(order.getStatus().name());
        response.setConvertedSaleId(order.getConvertedSaleId());
        response.setConvertedAt(order.getConvertedAt());
        response.setCancelledAt(order.getCancelledAt());
        response.setCancelReason(order.getCancelReason());
        response.setNotes(order.getNotes());

        List<OrderItemResponse> items = order.getItems().stream().map(item -> {
            OrderItemResponse itemResponse = new OrderItemResponse();
            itemResponse.setId(item.getId());
            itemResponse.setProductId(item.getProduct().getId());
            itemResponse.setProductName(item.getProduct().getName());
            itemResponse.setProductSku(item.getProduct().getSku());
            itemResponse.setQuantity(item.getQuantity());
            itemResponse.setUnitPrice(item.getUnitPrice());
            itemResponse.setTotalPrice(item.getTotalPrice());
            itemResponse.setTaxAmount(item.getTaxAmount());
            itemResponse.setCostPriceAtOrder(item.getCostPriceAtOrder());
            return itemResponse;
        }).collect(java.util.stream.Collectors.toList());
        response.setItems(items);
        response.setItemCount(items.size());

        return response;
    }

    private String buildCustomerDisplayName(Customer customer) {
        String contact = customer.getPhone() != null ? customer.getPhone()
                : (customer.getEmail() != null ? customer.getEmail() : null);
        return customer.getFirstName() + " " + customer.getLastName()
                + (contact != null ? " (" + contact + ")" : "");
    }

    private BigDecimal getShopTaxRate() {
        return shopInfoRepository.findTopByOrderByIdAsc()
                .map(com.bms.entity.ShopInfo::getTaxPercentage)
                .orElse(BigDecimal.ZERO);
    }

    // Mirrors SaleService.computeDiscount; orders have no cashier-entered amount,
    // so AMOUNT mode yields zero while FIXED/PERCENTAGE apply automatically.
    private BigDecimal computeShopDiscount(BigDecimal subtotal) {
        if (subtotal == null || subtotal.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        ShopInfo info = shopInfoRepository.findTopByOrderByIdAsc().orElse(null);
        if (info == null || !Boolean.TRUE.equals(info.getDiscountEnabled())) {
            return BigDecimal.ZERO;
        }
        BigDecimal discount;
        if (info.getDiscountType() == ShopInfo.DiscountType.AMOUNT) {
            discount = BigDecimal.ZERO;
        } else if (info.getDiscountType() == ShopInfo.DiscountType.FIXED) {
            discount = info.getDiscountValue() != null ? info.getDiscountValue() : BigDecimal.ZERO;
        } else {
            BigDecimal pct = info.getDiscountValue() != null ? info.getDiscountValue() : BigDecimal.ZERO;
            if (pct.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
            discount = subtotal.multiply(pct)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }
        if (discount.compareTo(BigDecimal.ZERO) < 0) discount = BigDecimal.ZERO;
        if (discount.compareTo(subtotal) > 0) discount = subtotal;
        return discount.setScale(2, RoundingMode.HALF_UP);
    }

    // Same allocation rule as SaleDiscountUtils: proportional to gross line value,
    // remainder on the last positive line (which becomes MAX(id) once persisted).
    private BigDecimal applyDiscountedLineTaxes(Order order, BigDecimal taxRate) {
        List<OrderItem> items = order.getItems();
        BigDecimal grossTotal = BigDecimal.ZERO;
        OrderItem remainderTarget = null;
        for (OrderItem item : items) {
            grossTotal = grossTotal.add(item.getTotalPrice());
            if (item.getTotalPrice().signum() > 0) remainderTarget = item;
        }
        BigDecimal discount = order.getDiscountAmount() != null ? order.getDiscountAmount() : BigDecimal.ZERO;
        if (discount.compareTo(grossTotal) > 0) discount = grossTotal;

        Map<OrderItem, BigDecimal> allocations = new java.util.HashMap<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (OrderItem item : items) {
            if (item == remainderTarget) continue;
            BigDecimal share = discount.signum() > 0 && grossTotal.signum() > 0 && item.getTotalPrice().signum() > 0
                    ? discount.multiply(item.getTotalPrice()).divide(grossTotal, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            allocations.put(item, share);
            allocated = allocated.add(share);
        }
        if (remainderTarget != null) {
            allocations.put(remainderTarget, discount.subtract(allocated).max(BigDecimal.ZERO));
        }

        BigDecimal headerTax = BigDecimal.ZERO;
        for (OrderItem item : items) {
            BigDecimal lineNet = item.getTotalPrice().subtract(allocations.getOrDefault(item, BigDecimal.ZERO));
            BigDecimal itemTax = lineNet.multiply(taxRate)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            item.setTaxAmount(itemTax);
            headerTax = headerTax.add(itemTax);
        }
        return headerTax;
    }

    private BelowCostPolicy getBelowCostPolicy() {
        return systemSettingRepository.findBySettingKey("shop.belowCostPolicy")
                .map(SystemSetting::getSettingValue)
                .map(BelowCostPolicy::fromString)
                .orElse(BelowCostPolicy.ALLOW_WARN);
    }

    private boolean isManagerOrAdmin(Long userId) {
        if (userId == null) {
            return false;
        }
        return userRepository.findById(userId)
                .map(u -> u.getRole() != null
                        && (u.getRole().getName() == Role.RoleName.ROLE_ADMIN
                            || u.getRole().getName() == Role.RoleName.ROLE_MANAGER))
                .orElse(false);
    }
}