package com.bms.service;

import com.bms.dto.request.CartVerifyRequest;
import com.bms.dto.request.RefundRequest;
import com.bms.dto.request.SaleCreateRequest;
import com.bms.dto.response.CartVerifyResponse;
import com.bms.dto.response.RefundResponse;
import com.bms.dto.response.SaleItemResponse;
import com.bms.dto.response.SaleResponse;
import com.bms.entity.*;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opencsv.CSVWriterBuilder;
import com.opencsv.ICSVWriter;

import java.io.Writer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

    public Page<Sale> getAllSales(Pageable pageable) {
        return saleRepository.findActiveSales(pageable);
    }

    public Page<Sale> getNonVoidedSales(Pageable pageable) {
        return saleRepository.findNonVoidedSales(pageable);
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

        // Generate invoice number
        String invoiceNumber = generateInvoiceNumber();

        Sale sale = new Sale();
        sale.setInvoiceNumber(invoiceNumber);
        sale.setCashierId(cashierId);
        sale.setSaleDate(LocalDateTime.now());
        // Payment method (CASH / CREDIT)
        String paymentMethod = request.getPaymentMethod();
        if (paymentMethod == null) {
            paymentMethod = "CASH";
        }

        if ("CASH".equals(paymentMethod)) {
            sale.setPaymentMethod(Sale.PaymentMethod.CASH);
        } else if ("CREDIT".equals(paymentMethod)) {
            sale.setPaymentMethod(Sale.PaymentMethod.CREDIT);
        } else {
            throw new BusinessException("Invalid paymentMethod. Use CASH or CREDIT");
        }

        sale.setNotes(request.getNotes());
        sale.setIsVoided(false);

        // Set customer if provided (walk-in if null). CREDIT always requires a customer.
        if (request.getCustomerId() != null) {
            Customer customer = customerRepository.findById(request.getCustomerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + request.getCustomerId()));
            sale.setCustomer(customer);
        } else if (sale.getPaymentMethod() == Sale.PaymentMethod.CREDIT) {
            throw new BusinessException("Customer is required for CREDIT sales");
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

            BigDecimal[] pricing = calculateItemPricing(product, itemRequest.getQuantity());
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

        // Discount computation (whole-sale level): applied before final total
        BigDecimal discountAmount = BigDecimal.ZERO;
        if (request.getDiscountType() != null && request.getDiscountValue() != null) {
            if ("PERCENTAGE".equals(request.getDiscountType())) {
                if (request.getDiscountValue().compareTo(BigDecimal.ZERO) < 0 ||
                    request.getDiscountValue().compareTo(BigDecimal.valueOf(100)) > 0) {
                    throw new BusinessException("Discount percentage must be between 0 and 100");
                }
                discountAmount = subtotal.multiply(request.getDiscountValue())
                        .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
            } else if ("FIXED".equals(request.getDiscountType())) {
                if (request.getDiscountValue().compareTo(BigDecimal.ZERO) < 0) {
                    throw new BusinessException("Fixed discount cannot be negative");
                }
                discountAmount = request.getDiscountValue();
            } else {
                throw new BusinessException("Invalid discount type. Use PERCENTAGE or FIXED");
            }

            BigDecimal saleTotalBeforeDiscount = subtotal.add(taxAmount);
            if (discountAmount.compareTo(saleTotalBeforeDiscount) > 0) {
                throw new BusinessException("Discount cannot exceed the sale total");
            }

            if (discountAmount.compareTo(BigDecimal.ZERO) > 0) {
                sale.setDiscountReason(request.getDiscountReason());
            }
        }

        sale.setDiscountAmount(discountAmount);

        BigDecimal totalAmount = subtotal.add(taxAmount).subtract(discountAmount);
        sale.setTotalAmount(totalAmount);

        // Payment + credit handling
        if (sale.getPaymentMethod() == Sale.PaymentMethod.CREDIT) {
            if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("Total amount must be greater than 0 for CREDIT sales");
            }
            if (request.getAmountPaid() == null) {
                throw new BusinessException("Amount paid is required");
            }
            if (request.getAmountPaid().compareTo(BigDecimal.ZERO) != 0) {
                throw new BusinessException("For CREDIT sales, amountPaid must be 0");
            }

            Customer customer = sale.getCustomer();
            if (customer == null) {
                throw new BusinessException("Customer is required for CREDIT sales");
            }

            BigDecimal newCreditBalance = customer.getCreditBalance().add(totalAmount);
            BigDecimal creditLimit = customer.getCreditLimit() != null ? customer.getCreditLimit() : BigDecimal.ZERO;

            if (newCreditBalance.compareTo(creditLimit) > 0) {
                throw new BusinessException("CREDIT limit exceeded");
            }

            sale.setAmountPaid(BigDecimal.ZERO);
            sale.setChangeGiven(BigDecimal.ZERO);

            Customer updatedCustomer = customer;
            updatedCustomer.setCreditBalance(newCreditBalance);
            customerRepository.save(updatedCustomer);
        } else {
            // CASH
            sale.setAmountPaid(request.getAmountPaid());

            BigDecimal changeGiven = request.getAmountPaid().subtract(totalAmount);
            if (changeGiven.compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException("Amount paid (" + request.getAmountPaid() +
                        ") is less than total amount (" + totalAmount + ")");
            }
            sale.setChangeGiven(changeGiven);
        }

        Sale savedSale = saleRepository.save(sale);

        // Process stock deductions
        processStockDeduction(savedSale, cashierId);

        auditLogService.logAction(cashierId, "SALE_CREATE",
                "Sale created: " + savedSale.getInvoiceNumber(),
                "Sale", savedSale.getId(), null, savedSale.toString());

        return convertToResponse(savedSale);
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
        String prefix = "INV-";
        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        List<Sale> matches = saleRepository.findLastInvoicesByPrefix(
                prefix + datePart, org.springframework.data.domain.PageRequest.of(0, 1));

        if (matches.isEmpty()) {
            return prefix + datePart + "-0001";
        }

        String lastNumber = matches.get(0).getInvoiceNumber();
        int seqNum = Integer.parseInt(lastNumber.substring(lastNumber.lastIndexOf("-") + 1));
        return prefix + datePart + "-" + String.format("%04d", seqNum + 1);
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

        // Restore stock for voided sale
        for (SaleItem item : sale.getItems()) {
            Product product = item.getProduct();
            int newStock = product.getStockQuantity() + item.getQuantity();
            product.setStockQuantity(newStock);
            productRepository.save(product);

            // Create stock movement record for restoration
            StockMovement movement = new StockMovement();
            movement.setProduct(product);
            movement.setMovementType(StockMovement.MovementType.ADJUSTMENT_IN);
            movement.setQuantity(item.getQuantity());
            movement.setReferenceType(StockMovement.ReferenceType.RETURN);
            movement.setReferenceId(sale.getId());
            movement.setDescription("Stock restored from voided sale: " + sale.getInvoiceNumber());
            User user = userRepository.findById(userId).orElse(null);
            movement.setCreatedBy(user);
            movement.setMovementDate(LocalDateTime.now());
            stockMovementRepository.save(movement);
        }

        auditLogService.logAction(userId, "SALE_VOID", 
            "Sale voided: " + sale.getInvoiceNumber() + ". Reason: " + reason, 
            "Sale", sale.getId(), oldValues, sale.toString());

        return convertToResponse(updatedSale);
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

        // If CREDIT sale, refund should reduce customer's credit_balance (allocate discount proportionally)
        // This uses the same allocatedDiscount/refundAmount computed per refunded item.
        Customer creditedCustomer = sale.getCustomer();

        for (RefundRequest.RefundItemRequest itemRequest : request.getItems()) {
            if (!requestedSaleItemIds.add(itemRequest.getSaleItemId())) {
                throw new BusinessException("Duplicate refund item: " + itemRequest.getSaleItemId());
            }

            SaleItem saleItem = sale.getItems().stream()
                    .filter(item -> item.getId().equals(itemRequest.getSaleItemId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException("Sale item does not belong to this sale: " + itemRequest.getSaleItemId()));

            int alreadyRefunded = saleItem.getQuantityRefunded() != null ? saleItem.getQuantityRefunded() : 0;
            int refundableQuantity = saleItem.getQuantity() - alreadyRefunded;
            int requestedQuantity = itemRequest.getQuantity();
            if (requestedQuantity > refundableQuantity) {
                throw new BusinessException("Cannot refund " + requestedQuantity + " of " + saleItem.getProduct().getName()
                        + ". Refundable quantity is " + refundableQuantity);
            }

            // Refund on discounted sales:
            // Discounts are applied at whole-sale level (sale.discountAmount).
            // Allocate the discount proportionally to the refunded item's pre-tax total
            // (saleItem.totalPrice corresponds to pre-tax line total).
            BigDecimal itemPreTaxTotal = saleItem.getUnitPrice()
                    .multiply(BigDecimal.valueOf(requestedQuantity))
                    .setScale(2, java.math.RoundingMode.HALF_UP);

            BigDecimal allocatedDiscount = BigDecimal.ZERO;
            if (sale.getDiscountAmount() != null
                    && sale.getDiscountAmount().compareTo(BigDecimal.ZERO) > 0
                    && sale.getSubtotal() != null
                    && sale.getSubtotal().compareTo(BigDecimal.ZERO) > 0) {

                allocatedDiscount = sale.getDiscountAmount()
                        .multiply(itemPreTaxTotal)
                        .divide(sale.getSubtotal(), 2, java.math.RoundingMode.HALF_UP);
            }

            BigDecimal refundAmount = itemPreTaxTotal.subtract(allocatedDiscount)
                    .setScale(2, java.math.RoundingMode.HALF_UP);

            if (refundAmount.compareTo(BigDecimal.ZERO) < 0) {
                refundAmount = BigDecimal.ZERO;
            }

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

        // Apply credit reversal only for CREDIT payments
        if (sale.getPaymentMethod() == Sale.PaymentMethod.CREDIT) {
            if (creditedCustomer == null) {
                throw new BusinessException("Customer missing for CREDIT refund reversal");
            }

            BigDecimal newCreditBalance = creditedCustomer.getCreditBalance().subtract(totalRefundAmount);
            if (newCreditBalance.compareTo(BigDecimal.ZERO) < 0) {
                newCreditBalance = BigDecimal.ZERO; // prevent negative credit balance
            }
            creditedCustomer.setCreditBalance(newCreditBalance);
            customerRepository.save(creditedCustomer);
        }

        refund.setTotalRefundAmount(totalRefundAmount);
        Refund savedRefund = refundRepository.save(refund);

        auditLogService.logAction(userId, "SALE_REFUND",
                "Refund processed for sale: " + sale.getInvoiceNumber() + ". Amount: " + totalRefundAmount,
                "Refund", savedRefund.getId(), null, savedRefund.toString());

        return convertRefundToResponse(savedRefund);
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

    public SaleResponse convertToResponse(Sale sale) {
        SaleResponse response = new SaleResponse();
        response.setId(sale.getId());
        response.setInvoiceNumber(sale.getInvoiceNumber());
        response.setCashierId(sale.getCashierId());
        response.setSaleDate(sale.getSaleDate());
        response.setSubtotal(sale.getSubtotal());
        response.setTaxAmount(sale.getTaxAmount());
        response.setDiscountAmount(sale.getDiscountAmount());
        response.setTotalAmount(sale.getTotalAmount());
        response.setAmountPaid(sale.getAmountPaid());
        response.setChangeGiven(sale.getChangeGiven());
        response.setPaymentMethod(sale.getPaymentMethod().name());
        response.setNotes(sale.getNotes());
        response.setIsVoided(sale.getIsVoided());
        response.setVoidedReason(sale.getVoidedReason());
        response.setCreatedAt(sale.getCreatedAt());

        if (sale.getCustomer() != null) {
            response.setCustomerId(sale.getCustomer().getId());
            response.setCustomerName(sale.getCustomer().getFirstName() + " " + sale.getCustomer().getLastName());
        } else {
            response.setCustomerName("Walk-in");
        }

        List<SaleItemResponse> itemResponses = sale.getItems().stream()
                .map(this::convertItemToResponse)
                .collect(Collectors.toList());
        response.setItems(itemResponses);

        return response;
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
            result.setTaxRate(product.getTaxRate());
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

            BigDecimal[] linePricing = calculateItemPricing(product, itemRequest.getQuantity());
            result.setLineTotal(linePricing[0]); // itemTotal (pre-tax)
            subtotal = subtotal.add(linePricing[0]);
            taxAmount = taxAmount.add(linePricing[1]);

            results.add(result);
        }

        // Apply discount to authoritative totals as part of verifyCart
        BigDecimal discountAmount = BigDecimal.ZERO;
        if (request.getDiscountType() != null && request.getDiscountValue() != null) {
            if ("PERCENTAGE".equals(request.getDiscountType())) {
                if (request.getDiscountValue().compareTo(BigDecimal.ZERO) < 0 ||
                        request.getDiscountValue().compareTo(BigDecimal.valueOf(100)) > 0) {
                    throw new BusinessException("Discount percentage must be between 0 and 100");
                }
                discountAmount = subtotal.multiply(request.getDiscountValue())
                        .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
            } else if ("FIXED".equals(request.getDiscountType())) {
                if (request.getDiscountValue().compareTo(BigDecimal.ZERO) < 0) {
                    throw new BusinessException("Fixed discount cannot be negative");
                }
                discountAmount = request.getDiscountValue();
            } else {
                throw new BusinessException("Invalid discount type. Use PERCENTAGE or FIXED");
            }

            BigDecimal saleTotalBeforeDiscount = subtotal.add(taxAmount);
            if (discountAmount.compareTo(saleTotalBeforeDiscount) > 0) {
                throw new BusinessException("Discount cannot exceed the sale total");
            }
        }

        BigDecimal totalAfterDiscount = subtotal.add(taxAmount).subtract(discountAmount);

        response.setItems(results);
        response.setSubtotal(subtotal);
        response.setTaxAmount(taxAmount);
        response.setTotalAmount(totalAfterDiscount);
        response.setMessages(messages);
        response.setValid(!anyChanged);
        return response;
    }

    public void exportSalesToCsv(Writer writer, LocalDate startDate, LocalDate endDate) {
        LocalDateTime start = startDate != null ? startDate.atStartOfDay() : LocalDate.now().minusMonths(3).atStartOfDay();
        LocalDateTime end = endDate != null ? endDate.plusDays(1).atStartOfDay() : LocalDate.now().plusDays(1).atStartOfDay();

        List<Sale> sales = saleRepository.findByDateRange(start, end, Pageable.unpaged()).getContent();

        ICSVWriter csvWriter = new CSVWriterBuilder(writer)
                .withSeparator(',')
                .build();

        csvWriter.writeNext(new String[]{
                "Invoice Number", "Sale Date", "Customer", "Cashier ID",
                "Subtotal", "Tax Amount", "Discount Amount", "Total Amount",
                "Amount Paid", "Change Given", "Payment Method", "Status", "Notes"
        });

        for (Sale sale : sales) {
            String status = sale.getIsVoided() != null && sale.getIsVoided() ? "VOIDED" : "COMPLETED";
            String customerName = sale.getCustomer() != null
                    ? sale.getCustomer().getFirstName() + " " + sale.getCustomer().getLastName()
                    : "Walk-in";

            csvWriter.writeNext(new String[]{
                    sale.getInvoiceNumber(),
                    sale.getSaleDate() != null ? sale.getSaleDate().toString() : "",
                    customerName,
                    String.valueOf(sale.getCashierId()),
                    sale.getSubtotal() != null ? sale.getSubtotal().toPlainString() : "0.00",
                    sale.getTaxAmount() != null ? sale.getTaxAmount().toPlainString() : "0.00",
                    sale.getDiscountAmount() != null ? sale.getDiscountAmount().toPlainString() : "0.00",
                    sale.getTotalAmount() != null ? sale.getTotalAmount().toPlainString() : "0.00",
                    sale.getAmountPaid() != null ? sale.getAmountPaid().toPlainString() : "0.00",
                    sale.getChangeGiven() != null ? sale.getChangeGiven().toPlainString() : "0.00",
                    sale.getPaymentMethod() != null ? sale.getPaymentMethod().name() : "CASH",
                    status,
                    sale.getNotes() != null ? sale.getNotes() : ""
            });
        }
    }

    // Shared by both verifyCart() and createSale() so the math can never drift apart again.
    // Returns [itemTotal, itemTax]
    private BigDecimal[] calculateItemPricing(Product product, Integer quantity) {
        BigDecimal itemTotal = product.getUnitPrice().multiply(new BigDecimal(quantity));
        BigDecimal itemTax = itemTotal.multiply(product.getTaxRate().divide(BigDecimal.valueOf(100)));
        return new BigDecimal[]{itemTotal, itemTax};
    }
}
