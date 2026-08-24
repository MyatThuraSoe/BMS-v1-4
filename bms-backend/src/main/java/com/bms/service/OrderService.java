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
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.CustomerRepository;
import com.bms.repository.OrderRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.ShopInfoRepository;
import com.bms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
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

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;
    private final UserRepository userRepository;
    private final ShopInfoRepository shopInfoRepository;
    private final SequenceService sequenceService;
    private final SaleService saleService;
    private final AuditLogService auditLogService;

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
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal taxAmount = BigDecimal.ZERO;

        // Validate + reserve stock while building items
        Map<Long, Integer> reservedByProduct = new java.util.HashMap<>();
        for (OrderCreateRequest.OrderItemRequest itemRequest : request.getItems()) {
            Product product = productRepository.findByIdForUpdate(itemRequest.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + itemRequest.getProductId()));
            if (!Boolean.TRUE.equals(product.getIsActive()) || product.getDeletedAt() != null) {
                throw new ResourceNotFoundException("Product not found: " + itemRequest.getProductId());
            }
            if (product.getAvailableQuantity() < itemRequest.getQuantity()) {
                throw new BusinessException("Insufficient available stock for product '" + product.getName()
                        + "'. Available: " + product.getAvailableQuantity() + ", Requested: " + itemRequest.getQuantity());
            }

            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProduct(product);
            item.setQuantity(itemRequest.getQuantity());
            item.setUnitPrice(product.getUnitPrice());
            item.setCostPriceAtOrder(product.getCostPrice());

            BigDecimal[] pricing = calculateItemPricing(product, itemRequest.getQuantity(), taxRate);
            item.setTotalPrice(pricing[0]);
            item.setTaxAmount(pricing[1]);

            order.getItems().add(item);
            subtotal = subtotal.add(pricing[0]);
            taxAmount = taxAmount.add(pricing[1]);

            reservedByProduct.merge(product.getId(), itemRequest.getQuantity(), Integer::sum);
        }

        order.setSubtotal(subtotal);
        order.setTaxAmount(taxAmount);
        order.setTotalAmount(subtotal.add(taxAmount));

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
        order.setConvertedAt(LocalDateTime.now());
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
        order.setCancelledAt(LocalDateTime.now());
        order.setCancelledBy(userId);
        order.setCancelReason(request != null ? request.getReason() : null);
        Order saved = orderRepository.save(order);

        auditLogService.logAction(userId, "ORDER_CANCEL",
                "Order cancelled: " + order.getOrderNumber()
                        + (request != null && request.getReason() != null ? ". Reason: " + request.getReason() : ""),
                "Order", saved.getId(), oldValues, saved.toString());

        return toResponse(saved);
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

    // Returns [itemTotal, itemTax], both rounded to 2dp.
    private BigDecimal[] calculateItemPricing(Product product, Integer quantity, BigDecimal taxRate) {
        BigDecimal itemTotal = product.getUnitPrice().multiply(new BigDecimal(quantity))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal itemTax = itemTotal.multiply(taxRate.divide(BigDecimal.valueOf(100)))
                .setScale(2, RoundingMode.HALF_UP);
        return new BigDecimal[]{itemTotal, itemTax};
    }
}