package com.bms.service;

import com.bms.dto.request.CartVerifyRequest;
import com.bms.dto.request.SaleCreateRequest;
import com.bms.dto.response.CartVerifyResponse;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
        sale.setPaymentMethod(Sale.PaymentMethod.CASH);
        sale.setNotes(request.getNotes());
        sale.setIsVoided(false);

        // Set customer if provided (walk-in if null)
        if (request.getCustomerId() != null) {
            Customer customer = customerRepository.findById(request.getCustomerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + request.getCustomerId()));
            sale.setCustomer(customer);
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
        sale.setDiscountAmount(BigDecimal.ZERO);
        
        BigDecimal totalAmount = subtotal.add(taxAmount);
        sale.setTotalAmount(totalAmount);
        sale.setAmountPaid(request.getAmountPaid());
        
        BigDecimal changeGiven = request.getAmountPaid().subtract(totalAmount);
        if (changeGiven.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("Amount paid (" + request.getAmountPaid() + 
                ") is less than total amount (" + totalAmount + ")");
        }
        sale.setChangeGiven(changeGiven);

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
        
        // Use database-level locking with SELECT FOR UPDATE to ensure atomicity
        // This works across multiple application instances
        return saleRepository.findLastInvoiceByPrefix(prefix + datePart)
                .map(lastSale -> {

                    String lastNumber = lastSale.getInvoiceNumber();

                    int seqNum = Integer.parseInt(
                            lastNumber.substring(lastNumber.lastIndexOf("-") + 1)
                    );

                    return prefix + datePart + "-" +
                            String.format("%04d", seqNum + 1);

                })
                .orElse(prefix + datePart + "-0001");
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

        response.setItems(results);
        response.setSubtotal(subtotal);
        response.setTaxAmount(taxAmount);
        response.setTotalAmount(subtotal.add(taxAmount));
        response.setMessages(messages);
        response.setValid(!anyChanged);
        return response;
    }

    // Shared by both verifyCart() and createSale() so the math can never drift apart again.
    // Returns [itemTotal, itemTax]
    private BigDecimal[] calculateItemPricing(Product product, Integer quantity) {
        BigDecimal itemTotal = product.getUnitPrice().multiply(new BigDecimal(quantity));
        BigDecimal itemTax = itemTotal.multiply(product.getTaxRate().divide(BigDecimal.valueOf(100)));
        return new BigDecimal[]{itemTotal, itemTax};
    }
}
