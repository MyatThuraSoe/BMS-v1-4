package com.bms.service;

import com.bms.dto.request.CartVerifyRequest;
import com.bms.dto.request.RefundRequest;
import com.bms.dto.request.SaleCreateRequest;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
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
    private RefundRepository refundRepository;

    @Autowired
    private SaleItemRepository saleItemRepository;

    @Autowired
    private CashShiftRepository cashShiftRepository;

    @Autowired
    private ShopInfoRepository shopInfoRepository;

    @Autowired
    private SequenceService sequenceService;

    @PersistenceContext
    private EntityManager entityManager;

    public Page<Sale> getAllSales(Pageable pageable) {
        return saleRepository.findActiveSales(pageable);
    }

    public Page<Sale> getNonVoidedSales(Pageable pageable) {
        return saleRepository.findNonVoidedSales(pageable);
    }

    public Page<Sale> getFilteredSales(String range, LocalDate startDate, LocalDate endDate, Long customerId, String invoice, Pageable pageable) {
        LocalDate now = LocalDate.now();
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
                    startDate = now.minusMonths(3);
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
            startDateTime, endDateTime, customerId,
            (invoice != null && !invoice.isBlank()) ? invoice : null,
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
        if (saleType == Sale.SaleType.CASH
                && (request.getAmountPaid() == null || request.getAmountPaid().compareTo(BigDecimal.ZERO) <= 0)) {
            throw new BusinessException("Amount paid is required and must be positive for cash sales");
        }

        // Credit invoices get a CR- prefixed, per-day locked sequence number.
        String invoiceNumber = saleType == Sale.SaleType.CREDIT
                ? sequenceService.nextCreditInvoiceNumber()
                : generateInvoiceNumber();

        Sale sale = new Sale();
        sale.setInvoiceNumber(invoiceNumber);
        sale.setCashierId(cashierId);
        sale.setSaleDate(LocalDateTime.now());
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
        if (saleType == Sale.SaleType.CREDIT) {
            creditCustomer = customerRepository.findByIdForUpdate(request.getCustomerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + request.getCustomerId()));
            if (!Boolean.TRUE.equals(creditCustomer.getIsActive()) || creditCustomer.getDeletedAt() != null) {
                throw new ResourceNotFoundException("Customer not found: " + request.getCustomerId());
            }
            sale.setCustomer(creditCustomer);
            sale.setCustomerDisplayName(buildCustomerDisplayName(creditCustomer));
        } else if (request.getCustomerId() != null) {
            Customer customer = customerRepository.findById(request.getCustomerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + request.getCustomerId()));
            sale.setCustomer(customer);
            sale.setCustomerDisplayName(buildCustomerDisplayName(customer));
        } else if (request.getCustomerName() != null && !request.getCustomerName().isBlank()) {
            sale.setCustomerDisplayName(request.getCustomerName().trim());
        } else {
            sale.setCustomerDisplayName("Walk-in");
        }

        // Cash sales MUST belong to an open cash shift — credit sales bypass it.
        if (saleType == Sale.SaleType.CASH) {
            CashShift openShift = cashShiftRepository.findByCashierIdAndStatus(cashierId, "OPEN")
                .orElseThrow(() -> new BusinessException(
                    "No open cash shift. Please open a shift before recording cash sales."));
            sale.setCashShiftId(openShift.getId());
        }

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal taxAmount = BigDecimal.ZERO;

        // First pass: validate all stock availability (lock product rows)
        for (SaleCreateRequest.SaleItemRequest itemRequest : request.getItems()) {
            Product product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + itemRequest.getProductId()));

            if (product.getStockQuantity() < itemRequest.getQuantity()) {
                throw new BusinessException("Insufficient stock for product '" + product.getName() +
                    "'. Available: " + product.getStockQuantity() + ", Requested: " + itemRequest.getQuantity());
            }
        }

        // Second pass: create items and calculate totals (use locked products)
        for (SaleCreateRequest.SaleItemRequest itemRequest : request.getItems()) {
            Product product = productRepository.findByIdForUpdate(itemRequest.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + itemRequest.getProductId()));

            SaleItem item = new SaleItem();
            item.setSale(sale);
            item.setProduct(product);
            item.setQuantity(itemRequest.getQuantity());
            item.setUnitPrice(product.getUnitPrice());
            item.setCostPriceAtSale(product.getCostPrice());

            BigDecimal[] pricing = calculateItemPricing(product, itemRequest.getQuantity(), getShopTaxRate());
            BigDecimal itemTotal = pricing[0];
            BigDecimal itemTax = pricing[1];
            item.setTotalPrice(itemTotal);
            item.setTaxAmount(itemTax);

            sale.getItems().add(item);

            subtotal = subtotal.add(itemTotal);
            taxAmount = taxAmount.add(itemTax);
        }

        sale.setSubtotal(subtotal);
        sale.setTaxAmount(taxAmount);
        sale.setDiscountAmount(BigDecimal.ZERO);

        BigDecimal totalAmount = subtotal.add(taxAmount);
        sale.setTotalAmount(totalAmount);

        if (saleType == Sale.SaleType.CREDIT) {
            // Credit-limit check BEFORE persisting anything
            BigDecimal newBalance = creditCustomer.getCurrentBalance().add(totalAmount);
            if (newBalance.compareTo(creditCustomer.getCreditLimit()) > 0) {
                throw new InsufficientCreditException(
                    "error.credit.limit.exceeded", creditCustomer.getCreditLimit());
            }
            sale.setAmountPaid(BigDecimal.ZERO);
            sale.setChangeGiven(BigDecimal.ZERO);
        } else {
            sale.setAmountPaid(request.getAmountPaid());

            BigDecimal changeGiven = request.getAmountPaid().subtract(totalAmount);
            if (changeGiven.compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException("Amount paid (" + request.getAmountPaid() +
                    ") is less than total amount (" + totalAmount + ")");
            }
            sale.setChangeGiven(changeGiven);
        }

        Sale savedSale = saleRepository.save(sale);

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
            
            product.setStockQuantity(newStock);
            productRepository.save(product);

            // Create stock movement record
            StockMovement movement = new StockMovement();
            movement.setProduct(product);
            movement.setMovementType(StockMovement.MovementType.OUT);
            movement.setQuantity(item.getQuantity());
            movement.setReferenceType(StockMovement.ReferenceType.SALE);
            movement.setReferenceId(sale.getId());
            movement.setDescription("Stock deducted from sale: " + sale.getInvoiceNumber());
            User user = userRepository.findById(userId).orElse(null);
            movement.setCreatedBy(user);
            movement.setMovementDate(LocalDateTime.now());
            stockMovementRepository.save(movement);
        }
    }

    private String generateInvoiceNumber() {
        return sequenceService.nextInvoiceNumber();
    }

    public SaleResponse voidSale(Long saleId, Long userId, String reason) {
        Sale sale = saleRepository.findById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("Sale not found: " + saleId));

        if (sale.getIsVoided()) {
            throw new BusinessException("Sale is already voided");
        }

        String oldValues = sale.toString();

        sale.setIsVoided(true);
        sale.setVoidedReason(reason);
        sale.setVoidedBy(userId);
        sale.setVoidedAt(LocalDateTime.now());

        Sale updatedSale = saleRepository.save(sale);

        // Restore stock for voided sale — only the portion that hasn't already
        // been restored via a prior refund on this item
        User user = userRepository.findById(userId).orElse(null);

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
            product.setStockQuantity(product.getStockQuantity() + quantityToRestore);
            productRepository.save(product);

            StockMovement movement = new StockMovement();
            movement.setProduct(product);
            movement.setMovementType(StockMovement.MovementType.ADJUSTMENT_IN);
            movement.setQuantity(quantityToRestore);
            movement.setReferenceType(StockMovement.ReferenceType.RETURN);
            movement.setReferenceId(sale.getId());
            movement.setDescription("Stock restored from voided sale: " + sale.getInvoiceNumber() +
                    (alreadyRefunded > 0 ? " (" + alreadyRefunded + " already restored via prior refund)" : ""));
            movement.setCreatedBy(user);
            movement.setMovementDate(LocalDateTime.now());
            stockMovementRepository.save(movement);
        }

        auditLogService.logAction(userId, "SALE_VOID", 
            "Sale voided: " + sale.getInvoiceNumber() + ". Reason: " + reason, 
            "Sale", sale.getId(), oldValues, sale.toString());

        // Voiding a credit sale reverses the increase in the customer's
        // outstanding balance for whatever is still owed on this invoice.
        if (sale.getSaleType() == Sale.SaleType.CREDIT
                && sale.getPaymentStatus() != Sale.PaymentStatus.PAID) {
            reverseCreditBalance(sale);
        }

        return convertToResponse(updatedSale);
    }

    private void reverseCreditBalance(Sale sale) {
        if (sale.getCustomer() == null) {
            return;
        }
        Customer creditCustomer = customerRepository.findByIdForUpdate(sale.getCustomer().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + sale.getCustomer().getId()));
        BigDecimal paid = sale.getAmountPaid() != null ? sale.getAmountPaid() : BigDecimal.ZERO;
        BigDecimal outstanding = sale.getTotalAmount().subtract(paid)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal newBalance = creditCustomer.getCurrentBalance().subtract(outstanding);
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            newBalance = BigDecimal.ZERO;
        }
        creditCustomer.setCurrentBalance(newBalance);
        customerRepository.save(creditCustomer);
    }

    public RefundResponse processRefund(Long saleId, RefundRequest request, Long userId) {
        Sale sale = saleRepository.findById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("Sale not found: " + saleId));

        if (sale.getIsVoided() != null && sale.getIsVoided()) {
            throw new BusinessException("Cannot refund a voided sale");
        }

        User refundedBy = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        Refund refund = new Refund();
        refund.setSale(sale);
        refund.setRefundedBy(refundedBy);
        refund.setRefundDate(LocalDateTime.now());
        refund.setReason(request.getReason());

        BigDecimal totalRefundAmount = BigDecimal.ZERO;
        Set<Long> requestedSaleItemIds = new HashSet<>();

        for (RefundRequest.RefundItemRequest itemRequest : request.getItems()) {
            if (!requestedSaleItemIds.add(itemRequest.getSaleItemId())) {
                throw new BusinessException("Duplicate refund item: " + itemRequest.getSaleItemId());
            }

            // Lock this specific sale item row before reading its refunded quantity —
            // prevents two concurrent refund requests from both reading "0 already refunded"
            // and both approving a refund that, combined, exceeds what was actually sold.
            SaleItem saleItem = saleItemRepository.findByIdForUpdate(itemRequest.getSaleItemId())
                    .orElseThrow(() -> new BusinessException("Sale item not found: " + itemRequest.getSaleItemId()));

            if (!saleItem.getSale().getId().equals(saleId)) {
                throw new BusinessException("Sale item does not belong to this sale: " + itemRequest.getSaleItemId());
            }

            int alreadyRefunded = saleItem.getQuantityRefunded() != null ? saleItem.getQuantityRefunded() : 0;
            int refundableQuantity = saleItem.getQuantity() - alreadyRefunded;
            int requestedQuantity = itemRequest.getQuantity();
            if (requestedQuantity > refundableQuantity) {
                throw new BusinessException("Cannot refund " + requestedQuantity + " of " + saleItem.getProduct().getName()
                        + ". Refundable quantity is " + refundableQuantity);
            }

            // Refund the tax-inclusive price (tax was charged at sale time,
            // so it must be returned too — otherwise refunds silently leak tax).
            BigDecimal itemTotal = saleItem.getTotalPrice() != null ? saleItem.getTotalPrice() : BigDecimal.ZERO;
            BigDecimal itemTax = saleItem.getTaxAmount() != null ? saleItem.getTaxAmount() : BigDecimal.ZERO;
            int itemQty = saleItem.getQuantity() != null && saleItem.getQuantity() > 0 ? saleItem.getQuantity() : 1;
            BigDecimal unitTotalInclTax = itemTotal.add(itemTax)
                    .divide(BigDecimal.valueOf(itemQty), 4, java.math.RoundingMode.HALF_UP);
            BigDecimal refundAmount = unitTotalInclTax
                    .multiply(BigDecimal.valueOf(requestedQuantity))
                    .setScale(2, java.math.RoundingMode.HALF_UP);

            Product product = productRepository.findByIdForUpdate(saleItem.getProduct().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + saleItem.getProduct().getId()));
            product.setStockQuantity(product.getStockQuantity() + requestedQuantity);
            productRepository.save(product);

            saleItem.setQuantityRefunded(alreadyRefunded + requestedQuantity);

            RefundItem refundItem = new RefundItem();
            refundItem.setRefund(refund);
            refundItem.setSaleItem(saleItem);
            refundItem.setQuantityRefunded(requestedQuantity);
            refundItem.setRefundAmount(refundAmount);
            refund.getItems().add(refundItem);

            StockMovement movement = new StockMovement();
            movement.setProduct(product);
            movement.setMovementType(StockMovement.MovementType.ADJUSTMENT_IN);
            movement.setQuantity(requestedQuantity);
            movement.setReferenceType(StockMovement.ReferenceType.RETURN);
            movement.setReferenceId(sale.getId());
            movement.setDescription("Stock restored from refund: " + sale.getInvoiceNumber());
            movement.setCreatedBy(refundedBy);
            movement.setMovementDate(LocalDateTime.now());
            stockMovementRepository.save(movement);

            totalRefundAmount = totalRefundAmount.add(refundAmount);
        }

        refund.setTotalRefundAmount(totalRefundAmount);
        Refund savedRefund = refundRepository.save(refund);

        // Refunds on credit invoices that are still UNPAID/PARTIAL should NOT
        // dispense cash — they reduce what the customer owes instead.
        if (sale.getSaleType() == Sale.SaleType.CREDIT
                && sale.getPaymentStatus() != Sale.PaymentStatus.PAID) {
            reverseCreditBalanceForRefund(sale, totalRefundAmount);
        }

        auditLogService.logAction(userId, "SALE_REFUND",
                "Refund processed for sale: " + sale.getInvoiceNumber() + ". Amount: " + totalRefundAmount,
                "Refund", savedRefund.getId(), null, savedRefund.toString());

        return convertRefundToResponse(savedRefund);
    }

    private void reverseCreditBalanceForRefund(Sale sale, BigDecimal refundAmount) {
        if (sale.getCustomer() == null) {
            return;
        }
        Customer creditCustomer = customerRepository.findByIdForUpdate(sale.getCustomer().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + sale.getCustomer().getId()));
        BigDecimal reduced = refundAmount != null ? refundAmount : BigDecimal.ZERO;
        BigDecimal newBalance = creditCustomer.getCurrentBalance().subtract(reduced)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            newBalance = BigDecimal.ZERO;
        }
        creditCustomer.setCurrentBalance(newBalance);
        customerRepository.save(creditCustomer);
    }

    public void deleteSale(Long id, Long userId) {
        Sale sale = saleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sale not found: " + id));
        
        sale.setDeletedAt(LocalDateTime.now());
        sale.setIsActive(false);
        saleRepository.save(sale);

        auditLogService.logAction(userId, "SALE_DELETE", 
            "Sale deleted: " + sale.getInvoiceNumber(), 
            "Sale", sale.getId(), sale.toString(), null);
    }

    public Map<String, Object> deleteSalesOlderThanYears(int years, Long userId) {
        if (years < 1) {
            throw new BusinessException("Years must be at least 1");
        }

        LocalDateTime cutoff = LocalDate.now().minusYears(years).atStartOfDay();
        List<Long> saleIds = entityManager.createQuery("""
                SELECT s.id
                FROM Sale s
                WHERE s.saleDate < :cutoff
                """, Long.class)
            .setParameter("cutoff", cutoff)
            .getResultList();

        if (saleIds.isEmpty()) {
            return Map.of(
                "deletedSales", 0,
                "cutoffDate", cutoff.toLocalDate().toString()
            );
        }

        entityManager.createQuery("""
                DELETE FROM RefundItem ri
                WHERE ri.refund.sale.id IN :saleIds
                   OR ri.saleItem.sale.id IN :saleIds
                """)
            .setParameter("saleIds", saleIds)
            .executeUpdate();

        entityManager.createQuery("""
                DELETE FROM Refund r
                WHERE r.sale.id IN :saleIds
                """)
            .setParameter("saleIds", saleIds)
            .executeUpdate();

        entityManager.createQuery("""
                DELETE FROM StockMovement sm
                WHERE sm.referenceId IN :saleIds
                  AND sm.referenceType IN (:referenceTypes)
                """)
            .setParameter("saleIds", saleIds)
            .setParameter("referenceTypes", List.of(
                StockMovement.ReferenceType.SALE,
                StockMovement.ReferenceType.RETURN
            ))
            .executeUpdate();

        entityManager.createQuery("""
                DELETE FROM ArPayment ap
                WHERE ap.invoice.id IN :saleIds
                """)
            .setParameter("saleIds", saleIds)
            .executeUpdate();

        entityManager.createQuery("""
                DELETE FROM SaleItem si
                WHERE si.sale.id IN :saleIds
                """)
            .setParameter("saleIds", saleIds)
            .executeUpdate();

        int deletedSales = entityManager.createQuery("""
                DELETE FROM Sale s
                WHERE s.id IN :saleIds
                """)
            .setParameter("saleIds", saleIds)
            .executeUpdate();

        auditLogService.logAction(userId, "SALE_DELETE_OLD",
            "Deleted sales older than " + cutoff.toLocalDate() + ". Count: " + deletedSales,
            "Sale", null, null, null);

        return Map.of(
            "deletedSales", deletedSales,
            "cutoffDate", cutoff.toLocalDate().toString()
        );
    }

    public SaleResponse convertToResponse(Sale sale) {
        List<Refund> refunds = refundRepository.findBySaleIdOrderByRefundDateDesc(sale.getId());
        return convertToResponse(sale, refunds);
    }

    public SaleResponse convertToResponse(Sale sale, List<Refund> refunds) {
        SaleResponse response = new SaleResponse();
        response.setId(sale.getId());
        response.setInvoiceNumber(sale.getInvoiceNumber());
        response.setCashierId(sale.getCashierId());
        userRepository.findById(sale.getCashierId()).ifPresent(cashier ->
            response.setCashierName(cashier.getFirstName() + " " + cashier.getLastName())
        );
        response.setSaleDate(sale.getSaleDate());
        response.setSubtotal(sale.getSubtotal());
        response.setTaxAmount(sale.getTaxAmount());
        response.setDiscountAmount(sale.getDiscountAmount());
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

        response.setRefunds(refunds.stream().map(this::convertRefundToResponse).collect(Collectors.toList()));
        response.setTotalRefunded(refunds.stream()
                .map(Refund::getTotalRefundAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));

        return response;
    }

    // Batch map a page of sales to DTOs so refunds reload in a single query instead of one per sale.
    public Page<SaleResponse> convertToResponses(Page<Sale> salePage) {
        List<Sale> sales = salePage.getContent();
        List<Long> saleIds = sales.stream().map(Sale::getId).toList();
        Map<Long, List<Refund>> refundsBySale = saleIds.isEmpty()
                ? Map.of()
                : refundRepository.findBySaleIdInOrderByRefundDateDesc(saleIds).stream()
                        .collect(Collectors.groupingBy(r -> r.getSale().getId()));
        return salePage.map(sale -> convertToResponse(sale, refundsBySale.getOrDefault(sale.getId(), List.of())));
    }

    public SaleItemResponse convertItemToResponse(SaleItem item) {
        SaleItemResponse response = new SaleItemResponse();
        response.setId(item.getId());
        response.setProductId(item.getProduct().getId());
        response.setProductName(item.getProduct().getName());
        response.setQuantity(item.getQuantity());
        response.setUnitPrice(item.getUnitPrice());
        response.setTotalPrice(item.getTotalPrice());
        response.setTaxAmount(item.getTaxAmount());
        response.setCostPriceAtSale(item.getCostPriceAtSale());
        response.setQuantityRefunded(item.getQuantityRefunded());
        return response;
    }

    private RefundResponse convertRefundToResponse(Refund refund) {
        RefundResponse response = new RefundResponse();
        response.setId(refund.getId());
        response.setSaleId(refund.getSale().getId());
        response.setInvoiceNumber(refund.getSale().getInvoiceNumber());
        response.setRefundedBy(refund.getRefundedBy().getId());
        response.setRefundedByUsername(refund.getRefundedBy().getUsername());
        response.setRefundDate(refund.getRefundDate());
        response.setReason(refund.getReason());
        response.setTotalRefundAmount(refund.getTotalRefundAmount());
        response.setItems(refund.getItems().stream().map(item -> {
            RefundResponse.RefundItemResponse itemResponse = new RefundResponse.RefundItemResponse();
            itemResponse.setId(item.getId());
            itemResponse.setSaleItemId(item.getSaleItem().getId());
            itemResponse.setProductId(item.getSaleItem().getProduct().getId());
            itemResponse.setProductName(item.getSaleItem().getProduct().getName());
            itemResponse.setQuantityRefunded(item.getQuantityRefunded());
            itemResponse.setRefundAmount(item.getRefundAmount());
            return itemResponse;
        }).collect(Collectors.toList()));
        return response;
    }

    public CartVerifyResponse verifyCart(CartVerifyRequest request) {
        CartVerifyResponse response = new CartVerifyResponse();
        List<CartVerifyResponse.CartVerifyItemResult> results = new java.util.ArrayList<>();
        List<String> messages = new java.util.ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal taxAmount = BigDecimal.ZERO;
        boolean anyChanged = false;

        for (CartVerifyRequest.CartVerifyItem itemRequest : request.getItems()) {
            Product product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + itemRequest.getProductId()));

            CartVerifyResponse.CartVerifyItemResult result = new CartVerifyResponse.CartVerifyItemResult();
            result.setProductId(product.getId());
            result.setProductName(product.getName());
            result.setQuantity(itemRequest.getQuantity());
            result.setUnitPrice(product.getUnitPrice());
            result.setTaxRate(getShopTaxRate());
            result.setAvailableStock(product.getStockQuantity());

            // BigDecimal: never use .equals() here, scale differs (e.g. 2.50 vs 2.5) — use compareTo
            boolean priceChanged = product.getUnitPrice().compareTo(itemRequest.getExpectedUnitPrice()) != 0;
            boolean insufficientStock = product.getStockQuantity() < itemRequest.getQuantity();
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
                        product.getName(), product.getStockQuantity(), itemRequest.getQuantity()));
            }

            BigDecimal[] linePricing = calculateItemPricing(product, itemRequest.getQuantity(), getShopTaxRate());
            result.setLineTotal(linePricing[0]); // itemTotal (pre-tax)
            subtotal = subtotal.add(linePricing[0]);
            taxAmount = taxAmount.add(linePricing[1]);

            results.add(result);
        }

        response.setItems(results);
        response.setSubtotal(subtotal);
        response.setTaxAmount(taxAmount);
        response.setTotalAmount(subtotal.add(taxAmount));
        response.setMessages(messages);
        response.setValid(!anyChanged);
        return response;
    }

    // Shared by both verifyCart() and createSale() so the math can never drift apart again.
    // Returns [itemTotal, itemTax], both rounded to 2dp BEFORE persisting so the
    // ledger never stores uncompressed decimals.
    private BigDecimal[] calculateItemPricing(Product product, Integer quantity, BigDecimal taxRate) {
        BigDecimal itemTotal = product.getUnitPrice().multiply(new BigDecimal(quantity))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal itemTax = itemTotal.multiply(taxRate.divide(BigDecimal.valueOf(100)))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        return new BigDecimal[]{itemTotal, itemTax};
    }

    // Tax percentage comes from the Shop Info configuration (admin-controlled),
    // applied uniformly on top of every line item total.
    private BigDecimal getShopTaxRate() {
        return shopInfoRepository.findTopByOrderByIdAsc()
                .map(ShopInfo::getTaxPercentage)
                .orElse(BigDecimal.ZERO);
    }

    public CustomerStatsResponse getCustomerStats(Long customerId) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
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
