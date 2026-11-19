package com.bms.service;

import com.bms.dto.request.CartVerifyRequest;
import com.bms.dto.request.SaleCreateRequest;
import com.bms.dto.request.SalePaymentRequest;
import com.bms.dto.response.*;
import com.bms.entity.*;
import com.bms.exception.BusinessException;
import com.bms.exception.InsufficientCreditException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class SaleService {

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private ProductRepository productRepository;


    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SaleReturnService saleReturnService;

    @Autowired
    private SaleItemRepository saleItemRepository;

    @Autowired
    private CashShiftRepository cashShiftRepository;

    @Autowired
    private ShopInfoRepository shopInfoRepository;

    @Autowired
    private SequenceService sequenceService;

    @Autowired
    private PromotionService promotionService;

    @Autowired
    private PaymentValidationService paymentValidationService;

    @Autowired
    private CashDrawerSessionService cashDrawerSessionService;

    @Autowired
    private SalePaymentRepository salePaymentRepository;

    @Autowired
    private SaleReturnRepository saleReturnRepository;

    @Autowired
    private ArPaymentRepository arPaymentRepository;

    @Autowired
    private SystemSettingRepository systemSettingRepository;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private PeriodGuard periodGuard;

    @Autowired
    private ShopTimeService shopTimeService;

    @Autowired
    private ManagerApprovalService managerApprovalService;

    @PersistenceContext
    private EntityManager entityManager;

    public Page<Sale> getAllSales(Pageable pageable) {
        return saleRepository.findActiveSales(pageable);
    }

    public Page<Sale> getNonVoidedSales(Pageable pageable) {
        return saleRepository.findNonVoidedSales(pageable);
    }

    public Page<Sale> getFilteredSales(String range, LocalDate startDate, LocalDate endDate, Long customerId, String invoice, Long cashierId, Boolean includeVoided, Pageable pageable) {
        LocalDate now = shopTimeService.today();
        if (range != null) {
            switch (range) {
                case "TODAY":
                case "today":
                    startDate = now;
                    endDate = now;
                    break;
                case "WEEK":
                case "week":
                    startDate = now.minusDays(now.getDayOfWeek().getValue() - 1);
                    endDate = now;
                    break;
                case "MONTH":
                case "month":
                    startDate = now.withDayOfMonth(1);
                    endDate = now;
                    break;
                case "QUARTER":
                case "quarter":
                    int quarterStartMonth = ((now.getMonthValue() - 1) / 3) * 3 + 1;
                    startDate = LocalDate.of(now.getYear(), quarterStartMonth, 1);
                    endDate = now;
                    break;
                case "YEAR":
                case "year":
                    startDate = now.withDayOfYear(1);
                    endDate = now;
                    break;
                case "ALL":
                case "all":
                    startDate = null;
                    endDate = null;
                    break;
            }
        }

        LocalDateTime startDateTime = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime endDateTime = endDate != null ? endDate.plusDays(1).atStartOfDay() : null;

        return saleRepository.findFilteredSales(
            includeVoided == null ? true : includeVoided,
            startDateTime, endDateTime, customerId,
            (invoice != null && !invoice.isBlank()) ? invoice : null,
            cashierId,
            pageable
        );
    }

    public SaleResponse getSaleById(Long id) {
        Sale sale = saleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sale not found: " + id));
        if (!sale.getIsActive() || sale.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Sale not found: " + id);
        }
        return convertToResponse(sale);
    }

    public SaleResponse getSaleByInvoiceNumber(String invoiceNumber) {
        Sale sale = saleRepository.findByInvoiceNumber(invoiceNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Sale not found: " + invoiceNumber));
        if (!sale.getIsActive() || sale.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Sale not found: " + invoiceNumber);
        }
        return convertToResponse(sale);
    }

    @Transactional(rollbackFor = Exception.class)
    public SaleResponse createSale(SaleCreateRequest request, Long cashierId) {
        periodGuard.assertOpen(shopTimeService.now());
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new BusinessException("Sale must have at least one item");
        }

        Sale.SaleType saleType = resolveSaleType(request.getSaleType());

        // Credit sales REQUIRE a registered customer and a due date.
        if (saleType == Sale.SaleType.CREDIT && request.getCustomerId() == null) {
            throw new IllegalArgumentException("error.credit.customer.required");
        }
        if (saleType == Sale.SaleType.CREDIT && request.getDueDate() == null) {
            throw new IllegalArgumentException("error.credit.dueDate.required");
        }

        // Credit invoices get a CR- prefixed, per-day locked sequence number.
        String invoiceNumber = saleType == Sale.SaleType.CREDIT
                ? sequenceService.nextCreditInvoiceNumber()
                : generateInvoiceNumber();

        Sale sale = new Sale();
        sale.setInvoiceNumber(invoiceNumber);
        sale.setCashierId(cashierId);
        sale.setSaleDate(shopTimeService.now());
        sale.setPaymentMethod(Sale.PaymentMethod.CASH);
        sale.setSaleType(saleType);
        sale.setPaymentStatus(saleType == Sale.SaleType.CREDIT
                ? Sale.PaymentStatus.UNPAID : Sale.PaymentStatus.PAID);
        sale.setDueDate(saleType == Sale.SaleType.CREDIT ? request.getDueDate() : null);
        sale.setNotes(request.getNotes());
        sale.setIsVoided(false);

        // Customer resolution. Credit sales lock the customer row with
        // PESSIMISTIC_WRITE from the start so concurrent credit sales
        // serialize on this customer and never race currentBalance.
        Customer creditCustomer = null;
        Customer pricingCustomer = null;
        if (saleType == Sale.SaleType.CREDIT) {
            creditCustomer = customerRepository.findByIdForUpdate(request.getCustomerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + request.getCustomerId()));
            if (!Boolean.TRUE.equals(creditCustomer.getIsActive()) || creditCustomer.getDeletedAt() != null) {
                throw new ResourceNotFoundException("Customer not found: " + request.getCustomerId());
            }
            sale.setCustomer(creditCustomer);
            sale.setCustomerDisplayName(buildCustomerDisplayName(creditCustomer));
            pricingCustomer = creditCustomer;
        } else if (request.getCustomerId() != null) {
            Customer customer = customerRepository.findById(request.getCustomerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + request.getCustomerId()));
            sale.setCustomer(customer);
            sale.setCustomerDisplayName(buildCustomerDisplayName(customer));
            pricingCustomer = customer;
        } else if (request.getCustomerName() != null && !request.getCustomerName().isBlank()) {
            sale.setCustomerDisplayName(request.getCustomerName().trim());
        } else {
            sale.setCustomerDisplayName("Walk-in");
        }

        // Every new sale belongs to an employee shift. Only cash payment
        // lines additionally require that shift to have an open drawer.
        CashShift openShift = paymentValidationService.requireOpenShift(cashierId);
        sale.setCashShiftId(openShift.getId());
        sale.setEmployeeShift(openShift);

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal promotionDiscount = BigDecimal.ZERO;
        BigDecimal priceListDiscount = BigDecimal.ZERO;

        Map<Long, Integer> requestedByProduct = new TreeMap<>();
        for (SaleCreateRequest.SaleItemRequest itemRequest : request.getItems()) {
            if (itemRequest.getQuantity() <= 0) {
                throw new BusinessException("Quantity must be positive");
            }
            requestedByProduct.merge(itemRequest.getProductId(), itemRequest.getQuantity(), Integer::sum);
        }

        // First pass: validate all stock availability (lock product rows)
        for (Map.Entry<Long, Integer> entry : requestedByProduct.entrySet()) {
            Product product = productRepository.findByIdForUpdate(entry.getKey())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + entry.getKey()));

            int available = product.getAvailableQuantity();
            if (available < entry.getValue()) {
                throw new BusinessException("Insufficient stock for product '" + product.getName() +
                    "'. Available: " + available + ", Requested: " + entry.getValue());
            }
        }

        // P10: resolve the below-cost policy once for this sale. The role lookup
        // only runs when the policy actually gates on it (REQUIRE_MANAGER).
        BelowCostPolicy belowCostPolicy = getBelowCostPolicy();
        boolean belowCostAllowedForUser = belowCostPolicy != BelowCostPolicy.REQUIRE_MANAGER
                || isManagerOrAdmin(cashierId);

        LocalDateTime pricingMoment = sale.getSaleDate();
        List<Promotion> activePromotions = promotionService.getActivePromotions(pricingMoment);

        // Second pass: create items and calculate totals (use locked products)
        for (SaleCreateRequest.SaleItemRequest itemRequest : request.getItems()) {
            Product product = productRepository.findByIdForUpdate(itemRequest.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + itemRequest.getProductId()));

            SaleItem item = new SaleItem();
            item.setSale(sale);
            item.setProduct(product);
            item.setQuantity(itemRequest.getQuantity());
            item.setCostPriceAtSale(product.getCostPrice());

            PricingQuote quote = promotionService.calculateBestPrice(
                    product, itemRequest.getQuantity(), pricingCustomer, pricingMoment, activePromotions);
            BigDecimal effectiveUnitPrice = quote.getUnitPrice();

            // P10/O5: the below-cost guard runs on the net price after the header
            // discount has been allocated, so it happens once for the whole cart
            // below instead of per line here.
            item.setOriginalUnitPrice(quote.getOriginalUnitPrice());
            item.setUnitPrice(effectiveUnitPrice);
            item.setLineDiscount(quote.getLineDiscount());
            item.setPromotionId(quote.getPromotionId());
            item.setPromotionName(quote.getPromotionName());

            BigDecimal itemTotal = lineGross(effectiveUnitPrice, itemRequest.getQuantity());
            item.setTotalPrice(itemTotal);

            sale.getItems().add(item);

            subtotal = subtotal.add(itemTotal);
            if ("PROMOTION".equals(quote.getSource())) {
                promotionDiscount = promotionDiscount.add(quote.getLineDiscount());
            } else if ("PRICE_LIST".equals(quote.getSource())) {
                priceListDiscount = priceListDiscount.add(quote.getLineDiscount());
            }
        }

        sale.setSubtotal(subtotal);
        sale.setPromotionDiscount(promotionDiscount.setScale(2, java.math.RoundingMode.HALF_UP));
        sale.setPriceListDiscount(priceListDiscount.setScale(2, java.math.RoundingMode.HALF_UP));
        // O5: one manager override covers both authority gates below.
        boolean managerOverride = approveManagerCredentials(
                request.getManagerUsername(), request.getManagerPassword(), cashierId);
        // M3: judge the discount that will actually be booked, not the raw request value.
        // In FIXED/PERCENTAGE modes computeDiscount ignores the request, so a sent amount
        // must not trip a limit for a discount that was never applied.
        BigDecimal discountAmount = computeDiscount(subtotal, request.getDiscountAmount());
        assertDiscountWithinAuthority(subtotal, discountAmount, cashierId, managerOverride);
        sale.setDiscountAmount(discountAmount);

        // P5: tax applies to the discounted line value, so the header discount
        // is allocated to lines before any tax is computed.
        BigDecimal taxAmount = applyDiscountedLineTaxes(sale, getShopTaxRate());
        sale.setTaxAmount(taxAmount);

        // O5: with the discount spread over the lines, the net unit prices are
        // final — this is where the below-cost policy applies.
        assertNetPriceRespectsCostPolicy(sale, belowCostPolicy,
                belowCostAllowedForUser || managerOverride);

        BigDecimal totalAmount = subtotal.subtract(discountAmount).add(taxAmount);
        sale.setTotalAmount(totalAmount);

        // Validate every split-payment line before the sale row or any stock
        // movement is written. A cash line requires this shift's open drawer.
        List<SalePaymentRequest> paymentLines = paymentValidationService.resolvePayments(
                request.getPayments(), totalAmount, saleType);
        paymentValidationService.verifyForSale(openShift, paymentLines, totalAmount, saleType);
        sale.setPaymentMethod(resolveLegacyPaymentMethod(paymentLines));

        if (saleType == Sale.SaleType.CREDIT) {
            // Credit-limit check BEFORE persisting anything.
            // Store credit (money the shop owes the customer) is not consumed at
            // sale time (phase 1: store credit is not a valid tender), so the
            // check is currentBalance + total against the limit, matching
            // createSaleFromOrder.
            BigDecimal newBalance = creditCustomer.getCurrentBalance().add(totalAmount);
            if (newBalance.compareTo(creditCustomer.getCreditLimit()) > 0) {
                throw new InsufficientCreditException(
                    "error.credit.limit.exceeded", creditCustomer.getCreditLimit());
            }
            sale.setAmountPaid(BigDecimal.ZERO);
            sale.setChangeGiven(BigDecimal.ZERO);
        } else {
            boolean hasCash = paymentLines.stream()
                    .anyMatch(line -> paymentValidationService.parseMethod(line.getMethod())
                            == com.bms.entity.PaymentMethod.CASH);
            BigDecimal tendered = request.getAmountPaid();
            if (hasCash) {
                if (paymentLines.size() > 1) {
                    // Split tender: lines are exact portions of the total, no cash change.
                    sale.setAmountPaid(totalAmount);
                    sale.setChangeGiven(BigDecimal.ZERO);
                } else {
                    // Single cash line: its amount equals totalAmount (verifyForSale enforces it).
                    if (tendered == null) {
                        tendered = totalAmount;
                    }
                    if (tendered.compareTo(totalAmount) < 0) {
                        throw new BusinessException("Amount paid (" + tendered
                                + ") is less than total amount (" + totalAmount + ")");
                    }
                    sale.setAmountPaid(tendered);
                    sale.setChangeGiven(tendered.subtract(totalAmount));
                }
            } else {
                // A non-cash sale collects exactly its payment lines, and
                // resolvePayments/verifyForSale already forced those to totalAmount.
                // An "amount paid" above the total is therefore money that was never
                // taken: crediting it to the customer minted store credit out of
                // nothing, and store credit offsets the credit limit, so a single
                // request could both invent a liability and unlock unbounded credit.
                // Change is a cash-only concept, so a mismatch is rejected.
                if (tendered != null && tendered.compareTo(totalAmount) != 0) {
                    throw new BusinessException("Amount paid (" + tendered
                            + ") must equal the total (" + totalAmount
                            + ") for a non-cash sale; change is only given on cash sales.");
                }
                sale.setAmountPaid(totalAmount);
                sale.setChangeGiven(BigDecimal.ZERO);
            }
        }

        Sale savedSale = saleRepository.save(sale);
        paymentValidationService.saveSalePayments(savedSale, openShift, paymentLines);

        // Credit sales raise the customer's outstanding balance by the full
        // invoice total (same transaction, customer row already locked).
        if (saleType == Sale.SaleType.CREDIT) {
            creditCustomer.setCurrentBalance(creditCustomer.getCurrentBalance().add(totalAmount));
            customerRepository.save(creditCustomer);
        }

        // Process stock deductions
        processStockDeduction(savedSale, cashierId);

        auditLogService.logAction(cashierId, "SALE_CREATE",
            "Sale created: " + savedSale.getInvoiceNumber(),
            "Sale", savedSale.getId(), null, savedSale.toString());

        // O5: every discount leaves a trail — who gave it and how much.
        if (discountAmount.compareTo(BigDecimal.ZERO) > 0) {
            auditLogService.logAction(cashierId, "SALE_DISCOUNT",
                    "Discount " + discountAmount + " on " + savedSale.getInvoiceNumber()
                            + " (subtotal " + subtotal + ")",
                    "Sale", savedSale.getId(), null, String.valueOf(subtotal));
        }

        return convertToResponse(savedSale);
    }

    private Sale.SaleType resolveSaleType(String saleType) {
        if (saleType == null || saleType.isBlank()) {
            return Sale.SaleType.CASH;
        }
        try {
            return Sale.SaleType.valueOf(saleType.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Invalid sale type: " + saleType);
        }
    }

    /**
     * Keeps the legacy single-payment column meaningful for old reports:
     * one method maps to itself, several map to MIXED.
     */
    private Sale.PaymentMethod resolveLegacyPaymentMethod(List<SalePaymentRequest> payments) {
        if (payments == null || payments.isEmpty()) {
            return Sale.PaymentMethod.CASH;
        }
        java.util.Set<com.bms.entity.PaymentMethod> methods = payments.stream()
                .map(line -> paymentValidationService.parseMethod(line.getMethod()))
                .collect(Collectors.toSet());
        if (methods.size() > 1) {
            return Sale.PaymentMethod.MIXED;
        }
        return legacyPaymentMethod(methods.iterator().next());
    }

    private Sale.PaymentMethod legacyPaymentMethod(com.bms.entity.PaymentMethod method) {
        try {
            return Sale.PaymentMethod.valueOf(method.name());
        } catch (IllegalArgumentException ex) {
            return Sale.PaymentMethod.OTHER;
        }
    }

    private String buildCustomerDisplayName(Customer customer) {
        String contact = customer.getPhone() != null ? customer.getPhone()
                : (customer.getEmail() != null ? customer.getEmail() : null);
        return customer.getFirstName() + " " + customer.getLastName() +
                (contact != null ? " (" + contact + ")" : "");
    }

    private void processStockDeduction(Sale sale, Long userId) {
        for (SaleItem item : sale.getItems()) {
            Product product = item.getProduct();
            
            int oldStock = product.getStockQuantity();
            int newStock = oldStock - item.getQuantity();
            
            if (newStock < 0) {
                throw new BusinessException("Stock cannot go below zero for product: " + product.getName());
            }

            // P1/O8: inventory relief at costPriceAtSale, posted before stock moves.
            BigDecimal costAtSale = item.getCostPriceAtSale() != null ? item.getCostPriceAtSale() : BigDecimal.ZERO;
            ledgerPostingService.postInventory(product, InventoryLedger.EventType.SALE,
                    "SALE", sale.getId(), String.valueOf(item.getId()), -item.getQuantity(),
                    costAtSale, costAtSale.multiply(BigDecimal.valueOf(item.getQuantity())).negate(),
                    null, "Sale " + sale.getInvoiceNumber(), sale.getSaleDate(), userId);
            ledgerPostingService.postSaleProfit(sale, item);

            product.setStockQuantity(newStock);
            productRepository.save(product);

            // Create stock movement record
            StockMovement movement = new StockMovement();
            movement.setProduct(product);
            movement.setMovementType(StockMovement.MovementType.OUT);
            movement.setQuantity(item.getQuantity());
            movement.setReferenceType(StockMovement.ReferenceType.SALE);
            movement.setReferenceId(sale.getId());
            movement.setSourceSaleId(sale.getId());
            movement.setDescription("Stock deducted from sale: " + sale.getInvoiceNumber());
            User user = userRepository.findById(userId).orElse(null);
            movement.setCreatedBy(user);
            movement.setMovementDate(shopTimeService.now());
            stockMovementRepository.save(movement);
        }
    }

    private String generateInvoiceNumber() {
        return sequenceService.nextInvoiceNumber();
    }

    /**
     * Converts a PENDING order into a real sale. The order already reserved
     * stock, so this method deducts physical stock AND clears the reservation
     * for every item — stock must never be counted twice.
     */
    public com.bms.dto.response.SaleResponse createSaleFromOrder(Order order, String paymentMethod,
                                                                  BigDecimal amountPaid, LocalDate dueDate,
                                                                  Long cashierId) {
        periodGuard.assertOpen(shopTimeService.now());
        String method = paymentMethod != null ? paymentMethod.trim().toUpperCase() : "CASH";
        boolean isCredit = "CREDIT".equals(method);
        com.bms.entity.PaymentMethod entityMethod;
        if (isCredit) {
            entityMethod = com.bms.entity.PaymentMethod.CREDIT;
        } else {
            entityMethod = paymentValidationService.parseMethod(method);
        }

        if (isCredit && order.getCustomer() == null) {
            throw new BusinessException("Credit orders require a registered customer");
        }
        if (isCredit && dueDate == null) {
            throw new IllegalArgumentException("error.credit.dueDate.required");
        }
        if (!isCredit && (amountPaid == null || amountPaid.compareTo(BigDecimal.ZERO) <= 0)) {
            throw new BusinessException("Amount paid is required and must be positive for cash sales");
        }

        String invoiceNumber = isCredit
                ? sequenceService.nextCreditInvoiceNumber()
                : generateInvoiceNumber();

        Sale sale = new Sale();
        sale.setInvoiceNumber(invoiceNumber);
        sale.setCashierId(cashierId);
        sale.setSaleDate(shopTimeService.now());
        sale.setPaymentMethod(isCredit ? Sale.PaymentMethod.CREDIT : legacyPaymentMethod(entityMethod));
        sale.setSaleType(isCredit ? Sale.SaleType.CREDIT : Sale.SaleType.CASH);
        sale.setPaymentStatus(isCredit ? Sale.PaymentStatus.UNPAID : Sale.PaymentStatus.PAID);
        sale.setDueDate(isCredit ? dueDate : null);
        sale.setIsVoided(false);
        sale.setNotes("From order " + order.getOrderNumber() +
                (order.getNotes() != null ? ". " + order.getNotes() : ""));

        Customer creditCustomer = null;
        if (isCredit) {
            creditCustomer = customerRepository.findByIdForUpdate(order.getCustomer().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + order.getCustomer().getId()));
            sale.setCustomer(creditCustomer);
            sale.setCustomerDisplayName(buildCustomerDisplayName(creditCustomer));
        } else if (order.getCustomer() != null) {
            Customer customer = customerRepository.findById(order.getCustomer().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + order.getCustomer().getId()));
            sale.setCustomer(customer);
            sale.setCustomerDisplayName(buildCustomerDisplayName(customer));
        } else {
            sale.setCustomerDisplayName(order.getCustomerDisplayName() != null ? order.getCustomerDisplayName() : "Walk-in");
        }

        // Every converted order belongs to the cashier's open employee shift.
        CashShift openShift = paymentValidationService.requireOpenShift(cashierId);
        sale.setCashShiftId(openShift.getId());
        sale.setEmployeeShift(openShift);

        BigDecimal subtotal = order.getSubtotal();
        BigDecimal taxAmount = order.getTaxAmount();
        BigDecimal totalAmount = order.getTotalAmount();

        // The order being converted still holds its own reservation, so stock checks
        // must NOT count that reservation against itself. Compute the order's reserved
        // share per product (aggregated, since a product can appear on multiple lines).
        Map<Long, Integer> orderReservedByProduct = new HashMap<>();
        for (OrderItem oi : order.getItems()) {
            orderReservedByProduct.merge(oi.getProduct().getId(), oi.getQuantity(), Integer::sum);
        }

        // F22: the demand that has to fit is the order's TOTAL for the product, so a
        // product on two lines is not checked twice against the same units.
        Map<Long, Integer> demandCheckedByProduct = new HashMap<>();
        // BUG-7: lock products in a consistent id order to avoid deadlocks between
        // concurrent conversions of overlapping orders.
        List<OrderItem> lockedItems = new ArrayList<>(order.getItems());
        lockedItems.sort(Comparator.comparing(oi -> oi.getProduct().getId()));
        for (OrderItem orderItem : lockedItems) {
            Product product = productRepository.findByIdForUpdate(orderItem.getProduct().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + orderItem.getProduct().getId()));

            int selfReserved = orderReservedByProduct.getOrDefault(orderItem.getProduct().getId(), 0);
            int neededSoFar = demandCheckedByProduct.merge(product.getId(), orderItem.getQuantity(), Integer::sum);
            int sellable = product.getStockQuantity()
                    - Math.max(0, product.getReservedQuantity() - selfReserved);
            if (sellable < neededSoFar) {
                throw new BusinessException("Insufficient stock for product '" + product.getName() +
                    "'. Available: " + sellable + ", Requested: " + neededSoFar);
            }

            SaleItem item = new SaleItem();
            item.setSale(sale);
            item.setProduct(product);
            item.setQuantity(orderItem.getQuantity());
            item.setUnitPrice(orderItem.getUnitPrice());
            item.setTotalPrice(orderItem.getTotalPrice());
            item.setTaxAmount(orderItem.getTaxAmount());
            // P2: COGS uses the cost in force at fulfilment (read under the product lock above),
            // not the order-time quote. costPriceAtOrder stays for margin quoting only.
            item.setCostPriceAtSale(product.getCostPrice());
            // O6: the pricing the order was created with has to survive onto the
            // sale line, otherwise promotion reports miss every converted order.
            item.setOriginalUnitPrice(orderItem.getOriginalUnitPrice());
            item.setLineDiscount(orderItem.getLineDiscount());
            item.setPromotionId(orderItem.getPromotionId());
            item.setPromotionName(orderItem.getPromotionName());
            sale.getItems().add(item);
        }

        // Below-cost check at fulfilment: cost may have changed since the order
        // was created, so re-check against the cost in force now (P2).
        BelowCostPolicy conversionBelowCostPolicy = getBelowCostPolicy();
        boolean conversionBelowCostAllowed = conversionBelowCostPolicy != BelowCostPolicy.REQUIRE_MANAGER
                || isManagerOrAdmin(cashierId);
        for (SaleItem item : sale.getItems()) {
            BigDecimal lineCost = item.getCostPriceAtSale();
            BigDecimal linePrice = item.getUnitPrice();
            if (lineCost != null && linePrice != null && linePrice.compareTo(lineCost) < 0) {
                if (conversionBelowCostPolicy == BelowCostPolicy.BLOCK) {
                    throw new BusinessException("Product '" + item.getProduct().getName()
                            + "' is priced below cost at fulfilment; below-cost sales are blocked");
                }
                if (conversionBelowCostPolicy == BelowCostPolicy.REQUIRE_MANAGER && !conversionBelowCostAllowed) {
                    throw new BusinessException("Product '" + item.getProduct().getName()
                            + "' is priced below cost at fulfilment; manager approval is required");
                }
            }
        }

        sale.setSubtotal(subtotal);
        sale.setTaxAmount(taxAmount);
        // O2: the order locked its prices (and shop discount) at creation; carry the
        // header discount so line allocation, refunds and reports match the order.
        sale.setDiscountAmount(order.getDiscountAmount() != null ? order.getDiscountAmount() : BigDecimal.ZERO);
        // O6: the split between cashier, promotion and price-list discount is
        // part of the locked order pricing and must reach the sale.
        sale.setPromotionDiscount(order.getPromotionDiscount());
        sale.setPriceListDiscount(order.getPriceListDiscount());
        sale.setTotalAmount(totalAmount);

        SalePaymentRequest paymentLine = new SalePaymentRequest();
        paymentLine.setMethod(entityMethod.name());
        paymentLine.setAmount(totalAmount);
        List<SalePaymentRequest> paymentLines = List.of(paymentLine);
        paymentValidationService.verifyForSale(openShift, paymentLines, totalAmount,
                isCredit ? Sale.SaleType.CREDIT : Sale.SaleType.CASH);

        if (isCredit) {
            BigDecimal newBalance = creditCustomer.getCurrentBalance().add(totalAmount);
            if (newBalance.compareTo(creditCustomer.getCreditLimit()) > 0) {
                throw new InsufficientCreditException(
                    "error.credit.limit.exceeded", creditCustomer.getCreditLimit());
            }
            sale.setAmountPaid(BigDecimal.ZERO);
            sale.setChangeGiven(BigDecimal.ZERO);
        } else {
            if (amountPaid.compareTo(totalAmount) < 0) {
                throw new BusinessException("Amount paid (" + amountPaid
                        + ") is less than total amount (" + totalAmount + ")");
            }
            if (entityMethod == com.bms.entity.PaymentMethod.CASH) {
                sale.setAmountPaid(amountPaid);
                sale.setChangeGiven(amountPaid.subtract(totalAmount));
            } else {
                // Non-cash tenders cannot produce cash change.
                sale.setAmountPaid(totalAmount);
                sale.setChangeGiven(BigDecimal.ZERO);
            }
        }

        Sale savedSale = saleRepository.save(sale);
        paymentValidationService.saveSalePayments(savedSale, openShift, paymentLines);

        if (isCredit) {
            creditCustomer.setCurrentBalance(creditCustomer.getCurrentBalance().add(totalAmount));
            customerRepository.save(creditCustomer);
        }

        // Deduct physical stock and clear the reservation in the same pass
        // F22: what this order still owes back, per product. Releasing the sold
        // quantity instead of the order's own reservation would eat into the
        // units other pending orders are holding whenever two orders share a
        // product, and those orders would then fail to convert.
        Map<Long, Integer> ownReservationToRelease = new HashMap<>(orderReservedByProduct);
        for (SaleItem item : savedSale.getItems()) {
            Product product = productRepository.findByIdForUpdate(item.getProduct().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + item.getProduct().getId()));
            int oldStock = product.getStockQuantity();
            int newStock = oldStock - item.getQuantity();
            if (newStock < 0) {
                throw new BusinessException("Stock cannot go below zero for product: " + product.getName());
            }
            int stillOurs = ownReservationToRelease.getOrDefault(product.getId(), 0);
            int release = Math.min(stillOurs, product.getReservedQuantity());
            int reservedForOthers = product.getReservedQuantity() - release;
            ownReservationToRelease.put(product.getId(), stillOurs - release);
            if (newStock < reservedForOthers) {
                throw new BusinessException("Converting this order would leave '" + product.getName()
                        + "' below the " + reservedForOthers
                        + " unit(s) other pending orders reserve; restock the product or cancel those orders first");
            }
            // P1/O8: inventory relief at fulfilment cost, posted before stock moves.
            BigDecimal costAtSale = item.getCostPriceAtSale() != null ? item.getCostPriceAtSale() : BigDecimal.ZERO;
            ledgerPostingService.postInventory(product, InventoryLedger.EventType.SALE,
                    "SALE", sale.getId(), String.valueOf(item.getId()), -item.getQuantity(),
                    costAtSale, costAtSale.multiply(BigDecimal.valueOf(item.getQuantity())).negate(),
                    null, "Sale (order conversion) " + savedSale.getInvoiceNumber(), sale.getSaleDate(), cashierId);
            ledgerPostingService.postSaleProfit(savedSale, item);
            product.setStockQuantity(newStock);
            product.setReservedQuantity(reservedForOthers);
            productRepository.save(product);

            StockMovement movement = new StockMovement();
            movement.setProduct(product);
            movement.setMovementType(StockMovement.MovementType.OUT);
            movement.setQuantity(item.getQuantity());
            movement.setReferenceType(StockMovement.ReferenceType.SALE);
            movement.setReferenceId(sale.getId());
            movement.setSourceSaleId(sale.getId());
            movement.setDescription("Stock deducted from sale (order conversion): " + savedSale.getInvoiceNumber());
            User user = userRepository.findById(cashierId).orElse(null);
            movement.setCreatedBy(user);
            movement.setMovementDate(shopTimeService.now());
            stockMovementRepository.save(movement);
        }

        auditLogService.logAction(cashierId, "SALE_CREATE",
            "Sale created from order " + order.getOrderNumber() + ": " + savedSale.getInvoiceNumber(),
            "Sale", savedSale.getId(), null, savedSale.toString());

        return convertToResponse(savedSale);
    }

    public SaleResponse voidSale(Long saleId, Long userId, String reason) {
        periodGuard.assertOpen(shopTimeService.now());
        Sale sale = saleRepository.findByIdForUpdate(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("Sale not found: " + saleId));

        if (sale.getIsVoided()) {
            throw new BusinessException("Sale is already voided");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("A reason is required to void a sale");
        }

        User voidingUser = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        // D5: managers may only void within 24 hours of the sale; admins may
        // void later, and the reason above is mandatory for every void.
        Role.RoleName roleName = voidingUser.getRole() != null ? voidingUser.getRole().getName() : null;
        boolean nonAdminVoid = !Role.RoleName.ROLE_ADMIN.equals(roleName);
        if (nonAdminVoid && sale.getSaleDate() != null) {
            LocalDateTime cutoff = shopTimeService.now().minusHours(24);
            if (!sale.getSaleDate().isAfter(cutoff)) {
                throw new BusinessException("Voids older than 24 hours require an administrator");
            }
        }

        String oldValues = sale.toString();

        // Resolve the cash reversal before mutating the sale so a missing open
        // drawer rejects the whole void instead of silently flipping isVoided.
        List<SalePayment> salePayments = salePaymentRepository
                .findBySaleIdOrderByPaidAtAsc(sale.getId());
        List<ArPayment> arPayments = arPaymentRepository != null
                ? arPaymentRepository.findByInvoiceIdOrderByPaymentDateAsc(sale.getId())
                : java.util.Collections.emptyList();

        BigDecimal cashPaid = salePayments.stream()
                .filter(payment -> payment.getPaymentMethod() == com.bms.entity.PaymentMethod.CASH)
                .map(SalePayment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal arCashPaid = arPayments.stream()
                .filter(payment -> payment.getReversedAt() == null
                        && payment.getPaymentMethod() == com.bms.entity.PaymentMethod.CASH)
                .map(ArPayment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal arNonCashPaid = arPayments.stream()
                .filter(payment -> payment.getReversedAt() == null
                        && payment.getPaymentMethod() != com.bms.entity.PaymentMethod.CASH)
                .map(ArPayment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cashAlreadyRefunded = safeSum(
                saleReturnRepository.sumRefundedAmountBySaleId(sale.getId()));
        BigDecimal cashToReturn = cashPaid.add(arCashPaid).subtract(cashAlreadyRefunded);

        CashShift voidShift = sale.getEmployeeShift();
        CashDrawerSession originalSession = voidShift != null ? voidShift.getDrawerSession() : null;
        CashDrawerSession reversalSession = null;
        boolean lateVoid = false;
        if (cashToReturn.compareTo(BigDecimal.ZERO) > 0) {
            if (originalSession != null && originalSession.getStatus() == DrawerSessionStatus.OPEN) {
                reversalSession = originalSession;
            } else {
                for (CashDrawer drawer : cashDrawerSessionService.getActiveDrawers()) {
                    reversalSession = cashDrawerSessionService.getOpenSession(drawer.getId());
                    if (reversalSession != null) {
                        break;
                    }
                }
                if (reversalSession == null) {
                    throw new BusinessException(
                            "No cash drawer is open. Open a drawer before voiding this cash sale.");
                }
                lateVoid = true;
            }
        }

        sale.setIsVoided(true);
        sale.setVoidedReason(reason);
        sale.setVoidedBy(userId);
        sale.setVoidedAt(shopTimeService.now());

        Sale updatedSale = saleRepository.save(sale);

        for (SalePayment payment : salePayments) {
            payment.setPaymentStatus(payment.getPaymentMethod() == com.bms.entity.PaymentMethod.CREDIT
                    ? PaymentStatus.CANCELLED
                    : PaymentStatus.REFUNDED);
            salePaymentRepository.save(payment);
        }

        if (reversalSession != null) {
            if (lateVoid) {
                // The original drawer is closed: its totals must stay frozen,
                // so the cash leaves today's drawer as a linked paid-out.
                final CashDrawerSession targetSession = reversalSession;
                CashShift performerShift = cashShiftRepository
                        .findByCashierIdAndStatus(userId, "OPEN")
                        .filter(shift -> shift.getShiftType() == ShiftType.CASH_DRAWER
                                && shift.getDrawerSession() != null
                                && java.util.Objects.equals(
                                        shift.getDrawerSession().getId(), targetSession.getId()))
                        .orElse(null);
                cashDrawerSessionService.recordFinancialMovement(
                        reversalSession,
                        DrawerMovementType.PAY_OUT,
                        cashToReturn.negate(),
                        "SALE",
                        sale.getId(),
                        voidingUser,
                        performerShift,
                        "Late void of sale " + sale.getInvoiceNumber()
                                + " (original drawer closed): " + reason);
            } else {
                cashDrawerSessionService.recordFinancialMovement(
                        reversalSession,
                        DrawerMovementType.CASH_REFUND,
                        cashToReturn.negate(),
                        "SALE",
                        sale.getId(),
                        voidingUser,
                        voidShift,
                        "Cash reversed for voided sale " + sale.getInvoiceNumber());
            }
        }


        // Restore stock for voided sale — only the portion that hasn't already
        // been restored via a prior refund on this item
        User user = voidingUser;

        for (SaleItem item : sale.getItems()) {
            // Lock the item row so a concurrent refund cannot restart/duplicate
            // the quantityRefunded math below (void restore must be consistent
            // with any refund that landed first).
            SaleItem lockedItem = saleItemRepository.findByIdForUpdate(item.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Sale item not found: " + item.getId()));

            int alreadyRefunded = lockedItem.getQuantityRefunded() != null ? lockedItem.getQuantityRefunded() : 0;
            int quantityToRestore = lockedItem.getQuantity() - alreadyRefunded;

            if (quantityToRestore <= 0) {
                // This item was already fully refunded before the void — nothing left to restore
                continue;
            }

            Product product = productRepository.findByIdForUpdate(lockedItem.getProduct().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + lockedItem.getProduct().getId()));

            // The profit reversal is unconditional: a voided sale must never keep
            // its revenue or COGS in the ledgers, whatever sold afterwards. Only
            // the inventory side depends on the units still being restorable.
            ledgerPostingService.postVoidProfit(sale, lockedItem, quantityToRestore, sale.getVoidedAt());

            // P1/O8: void restores inventory at costPriceAtSale, dated on the void.
            BigDecimal costAtSale = lockedItem.getCostPriceAtSale() != null ? lockedItem.getCostPriceAtSale() : BigDecimal.ZERO;
            ledgerPostingService.postInventory(product, InventoryLedger.EventType.VOID,
                    "SALE_VOID", sale.getId(), String.valueOf(lockedItem.getId()), quantityToRestore,
                    costAtSale, costAtSale.multiply(BigDecimal.valueOf(quantityToRestore)),
                    null, "Stock restored from voided sale " + sale.getInvoiceNumber(), sale.getVoidedAt(), userId);
            product.setStockQuantity(product.getStockQuantity() + quantityToRestore);
            productRepository.save(product);

            StockMovement movement = new StockMovement();
            movement.setProduct(product);
            movement.setMovementType(StockMovement.MovementType.ADJUSTMENT_IN);
            movement.setQuantity(quantityToRestore);
            movement.setReferenceType(StockMovement.ReferenceType.RETURN);
            movement.setReferenceId(sale.getId());
            movement.setSourceSaleId(sale.getId());
            movement.setDescription("Stock restored from voided sale: " + sale.getInvoiceNumber() +
                    (alreadyRefunded > 0 ? " (" + alreadyRefunded + " already restored via prior refund)" : ""));
            movement.setCreatedBy(user);
            movement.setMovementDate(shopTimeService.now());
            stockMovementRepository.save(movement);
        }

        auditLogService.logAction(userId, "SALE_VOID", 
            "Sale voided: " + sale.getInvoiceNumber() + ". Reason: " + reason, 
            "Sale", sale.getId(), oldValues, sale.toString());

        // Voiding a credit sale reverses the increase in the customer's
        // outstanding balance for whatever is still owed on this invoice.
        if (sale.getSaleType() == Sale.SaleType.CREDIT) {
            if (sale.getPaymentStatus() != Sale.PaymentStatus.PAID) {
                reverseCreditBalance(sale);
            }
            if (arPaymentRepository != null && !arPayments.isEmpty()) {
                LocalDateTime reversalAt = shopTimeService.now();
                Customer creditCustomer = sale.getCustomer() != null
                        ? customerRepository.findByIdForUpdate(sale.getCustomer().getId())
                        .orElse(null)
                        : null;
                BigDecimal convertedNonCash = BigDecimal.ZERO;
                for (ArPayment payment : arPayments) {
                    if (payment.getReversedAt() != null) {
                        continue;
                    }
                    payment.setReversedAt(reversalAt);
                    payment.setReversalNote("Voided sale " + sale.getInvoiceNumber() + ": " + reason);
                    arPaymentRepository.save(payment);
                    if (payment.getPaymentMethod() != PaymentMethod.CASH) {
                        convertedNonCash = convertedNonCash.add(payment.getAmount() != null ? payment.getAmount() : BigDecimal.ZERO);
                    }
                }
                if (creditCustomer != null && convertedNonCash.compareTo(BigDecimal.ZERO) > 0) {
                    // N3: only the credit balance grows here. The paid part was
                    // already taken off currentBalance when the customer paid, and
                    // whatever is still unpaid on this invoice is reversed by
                    // reverseCreditBalance(sale) above — subtracting again would
                    // cancel someone else's debt.
                    creditCustomer.setCreditBalance(creditCustomer.getCreditBalance()
                            .add(convertedNonCash).setScale(2, java.math.RoundingMode.HALF_UP));
                    customerRepository.save(creditCustomer);
                }
            }
        }

        return convertToResponse(updatedSale);
    }

    private void reverseCreditBalance(Sale sale) {
        if (sale.getCustomer() == null) {
            return;
        }

        // Returns on an unpaid/partial credit invoice have already lowered the
        // customer's balance by their return amounts. Only void-reverse what is
        // still genuinely outstanding for THIS invoice, otherwise a fully
        // returned sale would be reversed twice.
        BigDecimal alreadyReversedByReturns = BigDecimal.ZERO;
        for (SaleReturn ret : saleReturnService.findBySaleId(sale.getId())) {
            if (ret.getTotalReturnAmount() != null) {
                alreadyReversedByReturns = alreadyReversedByReturns.add(ret.getTotalReturnAmount());
            }
        }
        BigDecimal paid = sale.getAmountPaid() != null ? sale.getAmountPaid() : BigDecimal.ZERO;
        BigDecimal outstanding = sale.getTotalAmount().subtract(paid)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal toReverse = outstanding.subtract(alreadyReversedByReturns)
                .max(BigDecimal.ZERO)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        if (toReverse.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        Customer creditCustomer = customerRepository.findByIdForUpdate(sale.getCustomer().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + sale.getCustomer().getId()));
        BigDecimal newBalance = creditCustomer.getCurrentBalance().subtract(toReverse);
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            // O1: keep the excess as customer credit instead of dropping it.
            creditCustomer.setCreditBalance(creditCustomer.getCreditBalance()
                    .add(newBalance.negate()).setScale(2, java.math.RoundingMode.HALF_UP));
            newBalance = BigDecimal.ZERO;
        }
        creditCustomer.setCurrentBalance(newBalance);
        customerRepository.save(creditCustomer);
    }

    public SaleResponse convertToResponse(Sale sale) {
        List<SaleReturn> returns = saleReturnService.findBySaleId(sale.getId());
        return convertToResponse(sale, returns);
    }

    public SaleResponse convertToResponse(Sale sale, List<SaleReturn> returns) {
        return convertToResponse(sale, returns, Map.of());
    }

    public SaleResponse convertToResponse(Sale sale, List<SaleReturn> returns, Map<Long, String> cashierNames) {
        SaleResponse response = new SaleResponse();
        response.setId(sale.getId());
        response.setInvoiceNumber(sale.getInvoiceNumber());
        response.setCashierId(sale.getCashierId());
        String cashierName = cashierNames.get(sale.getCashierId());
        if (cashierName != null) {
            response.setCashierName(cashierName);
        } else {
            userRepository.findById(sale.getCashierId()).ifPresent(cashier ->
                response.setCashierName(cashier.getFirstName() + " " + cashier.getLastName())
            );
        }
        response.setSaleDate(sale.getSaleDate());
        response.setSubtotal(sale.getSubtotal());
        response.setTaxAmount(sale.getTaxAmount());
        response.setDiscountAmount(sale.getDiscountAmount());
        response.setPromotionDiscount(sale.getPromotionDiscount());
        response.setPriceListDiscount(sale.getPriceListDiscount());
        response.setTotalAmount(sale.getTotalAmount());
        response.setAmountPaid(sale.getAmountPaid());
        response.setChangeGiven(sale.getChangeGiven());
        response.setPaymentMethod(sale.getPaymentMethod().name());
        response.setSaleType(sale.getSaleType().name());
        response.setPaymentStatus(sale.getPaymentStatus().name());
        response.setDueDate(sale.getDueDate());
        response.setNotes(sale.getNotes());
        response.setIsVoided(sale.getIsVoided());
        response.setVoidedReason(sale.getVoidedReason());
        response.setCreatedAt(sale.getCreatedAt());

        if (sale.getCustomer() != null) {
            response.setCustomerId(sale.getCustomer().getId());
        }
        response.setCustomerName(sale.getCustomerDisplayName());

        List<SaleItemResponse> itemResponses = sale.getItems().stream()
                .map(this::convertItemToResponse)
                .collect(Collectors.toList());
        response.setItems(itemResponses);

        response.setReturns(returns.stream().map(saleReturnService::convertToResponse).collect(Collectors.toList()));
        response.setTotalReturned(returns.stream()
                .map(SaleReturn::getTotalReturnAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        response.setReturnStatus(sale.getReturnStatus().name());

        if (sale.getEmployeeShift() != null) {
            response.setEmployeeShiftId(sale.getEmployeeShift().getId());
            response.setShiftType(sale.getEmployeeShift().getShiftType().name());
        }
        response.setPayments(salePaymentRepository
                .findBySaleIdOrderByPaidAtAsc(sale.getId())
                .stream()
                .map(paymentValidationService::toResponse)
                .collect(Collectors.toList()));

        return response;
    }

    // Batch map a page of sales to DTOs so returns AND cashier names are loaded in single queries.
    public Page<SaleResponse> convertToResponses(Page<Sale> salePage) {
        List<Sale> sales = salePage.getContent();
        List<Long> saleIds = sales.stream().map(Sale::getId).toList();
        Map<Long, List<SaleReturn>> returnsBySale = saleReturnService.findBySaleIds(saleIds);
        Map<Long, String> cashierNames = loadCashierNames(sales);
        return salePage.map(sale -> convertToResponse(sale, returnsBySale.getOrDefault(sale.getId(), List.of()), cashierNames));
    }

    private Map<Long, String> loadCashierNames(List<Sale> sales) {
        Set<Long> cashierIds = sales.stream()
                .map(Sale::getCashierId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (cashierIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>(cashierIds.size() * 2);
        userRepository.findAllById(cashierIds).forEach(user ->
            names.put(user.getId(), user.getFirstName() + " " + user.getLastName()));
        return names;
    }

    public SaleItemResponse convertItemToResponse(SaleItem item) {
        SaleItemResponse response = new SaleItemResponse();
        response.setId(item.getId());
        response.setProductId(item.getProduct().getId());
        response.setProductName(item.getProduct().getName());
        response.setUnit(item.getProduct().getUnit());
        response.setQuantity(item.getQuantity());
        response.setUnitPrice(item.getUnitPrice());
        response.setOriginalUnitPrice(item.getOriginalUnitPrice());
        response.setLineDiscount(item.getLineDiscount());
        response.setPromotionId(item.getPromotionId());
        response.setPromotionName(item.getPromotionName());
        response.setTotalPrice(item.getTotalPrice());
        response.setTaxAmount(item.getTaxAmount());
        response.setCostPriceAtSale(item.getCostPriceAtSale());
        response.setQuantityRefunded(item.getQuantityRefunded());
        return response;
    }

    public CartVerifyResponse verifyCart(CartVerifyRequest request) {
        CartVerifyResponse response = new CartVerifyResponse();
        List<CartVerifyResponse.CartVerifyItemResult> results = new java.util.ArrayList<>();
        List<String> messages = new java.util.ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal promotionDiscount = BigDecimal.ZERO;
        BigDecimal priceListDiscount = BigDecimal.ZERO;
        boolean anyChanged = false;
        // Ephemeral lines mirroring the cart so verifyCart allocates the header
        // discount and computes tax exactly like createSale does.
        List<SaleItem> taxLines = new java.util.ArrayList<>();

        Customer pricingCustomer = request.getCustomerId() != null
                ? customerRepository.findById(request.getCustomerId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Customer not found: " + request.getCustomerId()))
                : null;
        BigDecimal taxRate = getShopTaxRate();
        LocalDateTime pricingMoment = shopTimeService.now();
        List<Promotion> activePromotions = promotionService.getActivePromotions(pricingMoment);

        for (CartVerifyRequest.CartVerifyItem itemRequest : request.getItems()) {
            Product product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + itemRequest.getProductId()));

            PricingQuote quote = promotionService.calculateBestPrice(
                    product, itemRequest.getQuantity(), pricingCustomer, pricingMoment, activePromotions);

            CartVerifyResponse.CartVerifyItemResult result = new CartVerifyResponse.CartVerifyItemResult();
            result.setProductId(product.getId());
            result.setProductName(product.getName());
            result.setQuantity(itemRequest.getQuantity());
            result.setUnitPrice(quote.getUnitPrice());
            result.setOriginalUnitPrice(quote.getOriginalUnitPrice());
            result.setLineDiscount(quote.getLineDiscount());
            result.setPromotionId(quote.getPromotionId());
            result.setPromotionName(quote.getPromotionName());
            result.setPricingSource(quote.getSource());
            result.setTaxRate(taxRate);
            result.setAvailableStock(product.getAvailableQuantity());

            // P10/O5: the below-cost flag is set once the header discount has been
            // allocated to the lines, so the POS warns about the price the
            // customer really pays. See the allocation pass below.

            // BigDecimal: never use .equals() here, scale differs (e.g. 2.50 vs 2.5) — use compareTo
            boolean priceChanged = product.getUnitPrice().compareTo(itemRequest.getExpectedUnitPrice()) != 0;
            boolean insufficientStock = product.getAvailableQuantity() < itemRequest.getQuantity();
            result.setPriceChanged(priceChanged);
            result.setInsufficientStock(insufficientStock);

            if (priceChanged) {
                anyChanged = true;
                messages.add(String.format("%s price changed from %s to %s",
                        product.getName(), itemRequest.getExpectedUnitPrice(), product.getUnitPrice()));
            }
            if (insufficientStock) {
                anyChanged = true;
                messages.add(String.format("%s: only %d in stock, %d requested",
                        product.getName(), product.getAvailableQuantity(), itemRequest.getQuantity()));
            }
            if (quote.getPromotionName() != null) {
                messages.add(String.format("%s: %s applied", product.getName(), quote.getPromotionName()));
            }

            BigDecimal lineTotal = lineGross(quote.getUnitPrice(), itemRequest.getQuantity());
            result.setLineTotal(lineTotal); // pre-tax gross; tax follows the discount allocation
            subtotal = subtotal.add(lineTotal);
            SaleItem taxLine = new SaleItem();
            taxLine.setQuantity(itemRequest.getQuantity());
            taxLine.setTotalPrice(lineTotal);
            taxLine.setProduct(product);
            taxLine.setCostPriceAtSale(product.getCostPrice());
            taxLines.add(taxLine);
            if ("PROMOTION".equals(quote.getSource())) {
                promotionDiscount = promotionDiscount.add(quote.getLineDiscount());
            } else if ("PRICE_LIST".equals(quote.getSource())) {
                priceListDiscount = priceListDiscount.add(quote.getLineDiscount());
            }

            results.add(result);
        }

        response.setItems(results);
        response.setSubtotal(subtotal);
        response.setPromotionDiscount(promotionDiscount.setScale(2, java.math.RoundingMode.HALF_UP));
        response.setPriceListDiscount(priceListDiscount.setScale(2, java.math.RoundingMode.HALF_UP));
        BigDecimal discountAmount = computeDiscount(subtotal, request.getDiscountAmount());
        response.setDiscountAmount(discountAmount);
        Sale taxCarrier = new Sale();
        taxCarrier.setItems(taxLines);
        taxCarrier.setDiscountAmount(discountAmount);
        BigDecimal taxAmount = applyDiscountedLineTaxes(taxCarrier, taxRate);
        response.setTaxAmount(taxAmount);
        response.setTotalAmount(subtotal.subtract(discountAmount).add(taxAmount));

        // O5: the discount is only known at cart level, so the below-cost flag is
        // derived from each line's net unit price after the allocation.
        Map<SaleItem, BigDecimal> cartAllocations = SaleDiscountUtils.allocateDiscounts(taxCarrier);
        for (int i = 0; i < taxLines.size(); i++) {
            SaleItem line = taxLines.get(i);
            BigDecimal lineNet = line.getTotalPrice()
                    .subtract(cartAllocations.getOrDefault(line, BigDecimal.ZERO));
            BigDecimal netUnit = lineNet.divide(BigDecimal.valueOf(line.getQuantity()), 4,
                    java.math.RoundingMode.HALF_UP);
            BigDecimal cost = line.getCostPriceAtSale();
            boolean belowCost = cost != null && netUnit.compareTo(cost) < 0;
            results.get(i).setBelowCost(belowCost);
            if (belowCost) {
                messages.add(String.format("%s is priced below cost after discount (net %s, cost %s)",
                        line.getProduct().getName(), netUnit.setScale(2, java.math.RoundingMode.HALF_UP), cost));
            }
        }

        response.setMessages(messages);
        response.setValid(!anyChanged);
        return response;
    }

    // Shared by both verifyCart() and createSale() so the math can never drift apart again.
    private BigDecimal lineGross(BigDecimal unitPrice, int quantity) {
        return unitPrice.multiply(BigDecimal.valueOf(quantity))
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Allocates the header discount across the sale's lines (proportional,
     * rounding remainder on the last positive line) and stores each line's tax
     * computed on its discounted base. Returns the header tax, which is always
     * the exact sum of the stored line taxes. Refunds add back this stored tax,
     * so they automatically return what was actually paid.
     */
    private BigDecimal applyDiscountedLineTaxes(Sale sale, BigDecimal taxRate) {
        Map<SaleItem, BigDecimal> allocations = SaleDiscountUtils.allocateDiscounts(sale);
        BigDecimal headerTax = BigDecimal.ZERO;
        for (SaleItem item : sale.getItems()) {
            BigDecimal lineNet = item.getTotalPrice()
                    .subtract(allocations.getOrDefault(item, BigDecimal.ZERO));
            BigDecimal itemTax = lineNet
                    .multiply(taxRate.divide(BigDecimal.valueOf(100), 6, java.math.RoundingMode.HALF_UP))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            item.setTaxAmount(itemTax);
            headerTax = headerTax.add(itemTax);
        }
        return headerTax;
    }

    // Tax percentage comes from the Shop Info configuration (admin-controlled),
    // applied uniformly on top of every line item total.
    private BigDecimal getShopTaxRate() {
        return shopInfoRepository.findTopByOrderByIdAsc()
                .map(ShopInfo::getTaxPercentage)
                .orElse(BigDecimal.ZERO);
    }

    // P10: below-cost policy is admin-controlled via the shop.belowCostPolicy
    // system setting. Missing or invalid config defaults to ALLOW_WARN so the
    // cashier flow is never silently blocked by a bad value.
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

    // Discount config comes from Shop Info (admin-controlled):
    //  - PERCENTAGE mode: discountValue% off the subtotal, applied automatically
    //  - FIXED mode: discountValue amount off every sale, applied automatically
    //  - AMOUNT mode: cashier-entered amount (clamped to [0, subtotal])
    // Disabled (or missing config) always yields ZERO. Mirrors getShopTaxRate().
    private BigDecimal computeDiscount(BigDecimal subtotal, BigDecimal requestedAmount) {
        if (subtotal == null || subtotal.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        ShopInfo info = shopInfoRepository.findTopByOrderByIdAsc().orElse(null);
        if (info == null || !Boolean.TRUE.equals(info.getDiscountEnabled())) {
            return BigDecimal.ZERO;
        }
        BigDecimal discount;
        if (info.getDiscountType() == ShopInfo.DiscountType.AMOUNT) {
            discount = requestedAmount != null ? requestedAmount : BigDecimal.ZERO;
        } else if (info.getDiscountType() == ShopInfo.DiscountType.FIXED) {
            discount = info.getDiscountValue() != null ? info.getDiscountValue() : BigDecimal.ZERO;
        } else {
            BigDecimal pct = info.getDiscountValue() != null ? info.getDiscountValue() : BigDecimal.ZERO;
            if (pct.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
            discount = subtotal.multiply(pct)
                    .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
        }
        if (discount.compareTo(BigDecimal.ZERO) < 0) discount = BigDecimal.ZERO;
        if (discount.compareTo(subtotal) > 0) discount = subtotal;
        return discount.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * O5/M3: a cashier-entered discount is capped at a configurable share of the subtotal.
     * The cap only applies in AMOUNT mode, where the cashier chose the figure. In FIXED and
     * PERCENTAGE modes the shop owner set the discount on purpose, so the cashier limit is
     * skipped - the amount was never the cashier's to authorise. Managers and administrators
     * get their own (higher) cap and a cashier can clear the cap with manager credentials the
     * same way an over-shipment is approved.
     */
    private void assertDiscountWithinAuthority(BigDecimal subtotal, BigDecimal entered, Long cashierId,
                                               boolean managerOverride) {
        if (entered == null || entered.compareTo(BigDecimal.ZERO) <= 0
                || subtotal == null || subtotal.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if (!shopDiscountIsCashierEntered()) {
            return;
        }
        boolean manager = isManagerOrAdmin(cashierId);
        BigDecimal limitPercent = discountLimitPercent(manager);
        BigDecimal maxAmount = subtotal.multiply(limitPercent)
                .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
        if (entered.compareTo(maxAmount) <= 0) {
            return;
        }
        if (!manager && managerOverride) {
            return;
        }
        throw new BusinessException("Discount " + entered.setScale(2, java.math.RoundingMode.HALF_UP)
                + " exceeds the allowed " + limitPercent.stripTrailingZeros().toPlainString()
                + "% of this sale (max " + maxAmount + "); manager approval is required");
    }

    /**
     * M3: whether the figure under review was chosen by the cashier (AMOUNT mode) or fixed by
     * the shop (FIXED / PERCENTAGE). The cashier cap only means something for the former, so a
     * shop-set percentage is not the cashier's decision to police. A shop that wants its own
     * percentages watched anyway can switch shop.discount.limitShopDiscounts on.
     */
    private boolean shopDiscountIsCashierEntered() {
        ShopInfo info = shopInfoRepository.findTopByOrderByIdAsc().orElse(null);
        if (info == null || info.getDiscountType() == ShopInfo.DiscountType.AMOUNT) {
            return true;
        }
        return systemSettingRepository.findBySettingKey("shop.discount.limitShopDiscounts")
                .map(SystemSetting::getSettingValue).map("true"::equalsIgnoreCase)
                .orElse(false);
    }

    /** O5: the percentage cap for the acting role, falling back to the shipped defaults. */
    private BigDecimal discountLimitPercent(boolean manager) {
        String key = manager ? "shop.discount.maxPercentManager" : "shop.discount.maxPercentCashier";
        BigDecimal fallback = manager ? new BigDecimal("100") : new BigDecimal("10");
        String raw = systemSettingRepository.findBySettingKey(key)
                .map(SystemSetting::getSettingValue).orElse(null);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            BigDecimal value = new BigDecimal(raw);
            return value.signum() < 0 ? fallback : value;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /**
     * O5/M2: the sale's manager override, now through the one shared approval path so a
     * cashier guessing at a manager password is counted and audited like a login attempt.
     * Silent when no credentials were entered; that is a normal sale with no override.
     */
    private boolean approveManagerCredentials(String username, String password, Long cashierId) {
        return managerApprovalService.verify(username, password,
                ManagerApprovalService.Purpose.SALE_DISCOUNT, cashierId);
    }

    /**
     * O5/P10: the below-cost policy judges the price the customer actually pays,
     * so it runs after the header discount has been allocated to the lines.
     */
    private void assertNetPriceRespectsCostPolicy(Sale sale, BelowCostPolicy policy, boolean allowedForUser) {
        if (policy == BelowCostPolicy.ALLOW_WARN) {
            return;
        }
        Map<SaleItem, BigDecimal> allocations = SaleDiscountUtils.allocateDiscounts(sale);
        for (SaleItem item : sale.getItems()) {
            BigDecimal lineNet = item.getTotalPrice()
                    .subtract(allocations.getOrDefault(item, BigDecimal.ZERO));
            BigDecimal netUnit = lineNet.divide(BigDecimal.valueOf(item.getQuantity()), 4,
                    java.math.RoundingMode.HALF_UP);
            BigDecimal cost = item.getCostPriceAtSale();
            if (cost == null || netUnit.compareTo(cost) >= 0) {
                continue;
            }
            if (policy == BelowCostPolicy.BLOCK) {
                throw new BusinessException("Product '" + item.getProduct().getName()
                        + "' is priced below cost; below-cost sales are blocked");
            }
            if (!allowedForUser) {
                throw new BusinessException("Product '" + item.getProduct().getName()
                        + "' is priced below cost; manager approval is required");
            }
        }
    }

    public CustomerStatsResponse getCustomerStats(Long customerId) {
        java.time.LocalDateTime now = shopTimeService.now();
        java.time.LocalDateTime startOfWeek = now.with(java.time.DayOfWeek.MONDAY).toLocalDate().atStartOfDay();
        java.time.LocalDateTime startOfMonth = now.withDayOfMonth(1).toLocalDate().atStartOfDay();
        java.time.LocalDateTime startOfYear = now.withDayOfYear(1).toLocalDate().atStartOfDay();
        java.time.LocalDateTime startOfTime = java.time.LocalDateTime.of(2000, 1, 1, 0, 0);

        CustomerStatsResponse stats = new CustomerStatsResponse();
        stats.setTotalSpentAllTime(safeSum(saleRepository.sumTotalAmountByCustomerIdAndDateAfter(customerId, startOfTime)));
        stats.setTotalSpentThisWeek(safeSum(saleRepository.sumTotalAmountByCustomerIdAndDateAfter(customerId, startOfWeek)));
        stats.setTotalSpentThisMonth(safeSum(saleRepository.sumTotalAmountByCustomerIdAndDateAfter(customerId, startOfMonth)));
        stats.setTotalSpentThisYear(safeSum(saleRepository.sumTotalAmountByCustomerIdAndDateAfter(customerId, startOfYear)));
        stats.setTotalInvoices(saleRepository.countInvoicesByCustomerId(customerId) != null ? saleRepository.countInvoicesByCustomerId(customerId) : 0L);

        return stats;
    }

    private java.math.BigDecimal safeSum(java.math.BigDecimal value) {
        return value != null ? value : java.math.BigDecimal.ZERO;
    }



    public List<CustomerTopProductResponse> getCustomerTopProducts(Long customerId) {
        org.springframework.data.domain.Pageable topTen = org.springframework.data.domain.PageRequest.of(0, 10);
        List<Object[]> results = saleRepository.findTopProductsByCustomerId(customerId, topTen);

        return results.stream().map(row -> {
            CustomerTopProductResponse response = new CustomerTopProductResponse();
            response.setProductId(((Number) row[0]).longValue());
            response.setProductName((String) row[1]);
            response.setTotalQuantity(((Number) row[2]).longValue());
            response.setTotalAmount((java.math.BigDecimal) row[3]);
            return response;
        }).collect(java.util.stream.Collectors.toList());
    }

    public List<CustomerDailySpendingResponse> getCustomerDailySpending(Long customerId, int year) {
        List<Object[]> results = saleRepository.findDailySpendingByCustomerIdAndYear(customerId, year);

        return results.stream().map(row -> new CustomerDailySpendingResponse(
                ((java.sql.Date) row[0]).toLocalDate(),
                (BigDecimal) row[1]
        )).collect(java.util.stream.Collectors.toList());
    }
}
