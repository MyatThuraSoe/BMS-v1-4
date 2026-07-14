package com.bms.service;

import com.bms.dto.request.PurchaseCreateRequest;
import com.bms.dto.request.PurchasePaymentStatusUpdateRequest;
import com.bms.dto.response.PurchaseItemResponse;
import com.bms.dto.response.PurchaseResponse;
import com.bms.entity.Product;
import com.bms.entity.Purchase;
import com.bms.entity.PurchaseItem;
import com.bms.entity.StockMovement;
import com.bms.entity.Supplier;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.ProductRepository;
import com.bms.repository.PurchaseRepository;
import com.bms.repository.StockMovementRepository;
import com.bms.repository.SupplierRepository;
import com.bms.repository.UserRepository;
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
public class PurchaseService {

    @Autowired
    private PurchaseRepository purchaseRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private UserRepository userRepository;

    public Page<PurchaseResponse> getAllPurchases(Pageable pageable) {
        return purchaseRepository.findActivePurchases(pageable).map(this::mapToResponse);
    }

    public PurchaseResponse getPurchaseById(Long id) {
        Purchase purchase = purchaseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found: " + id));
        if (!purchase.getIsActive() || purchase.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Purchase not found: " + id);
        }
        return mapToResponse(purchase);
    }

    public PurchaseResponse getPurchaseByNumber(String purchaseNumber) {
        Purchase purchase = purchaseRepository.findByPurchaseNumber(purchaseNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found: " + purchaseNumber));
        if (!purchase.getIsActive() || purchase.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Purchase not found: " + purchaseNumber);
        }
        return mapToResponse(purchase);
    }

    public PurchaseResponse createPurchase(PurchaseCreateRequest request, Long userId) {
        Supplier supplier = supplierRepository.findById(request.getSupplierId())
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found: " + request.getSupplierId()));

        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new BusinessException("Purchase must have at least one item");
        }

        // Generate purchase number
        String purchaseNumber = generatePurchaseNumber();

        Purchase purchase = new Purchase();
        purchase.setPurchaseNumber(purchaseNumber);
        purchase.setSupplier(supplier);
        purchase.setPurchaseDate(LocalDate.parse(request.getPurchaseDate(), DateTimeFormatter.ISO_LOCAL_DATE));
        purchase.setCreatedBy(userId);
        purchase.setNotes(request.getNotes());

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal taxAmount = BigDecimal.ZERO;

        for (PurchaseCreateRequest.PurchaseItemRequest itemRequest : request.getItems()) {
            Product product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + itemRequest.getProductId()));

            PurchaseItem item = new PurchaseItem();
            item.setPurchase(purchase);
            item.setProduct(product);
            item.setQuantity(itemRequest.getQuantity());
            item.setUnitCost(itemRequest.getUnitCost());
            item.setTotalCost(itemRequest.getUnitCost().multiply(new BigDecimal(itemRequest.getQuantity())));

            purchase.getItems().add(item);

            subtotal = subtotal.add(item.getTotalCost());
        }

        purchase.setSubtotal(subtotal);
        purchase.setTaxAmount(taxAmount);
        purchase.setTotalAmount(subtotal.add(taxAmount));

        Purchase savedPurchase = purchaseRepository.save(purchase);

        // Process stock increases
        processStockIncrease(savedPurchase, userId);

        auditLogService.logAction(userId, "PURCHASE_CREATE", 
            "Purchase created: " + savedPurchase.getPurchaseNumber(), 
            "Purchase", savedPurchase.getId(), null, savedPurchase.toString());

        return mapToResponse(savedPurchase);
    }

    private PurchaseResponse mapToResponse(Purchase purchase) {
        PurchaseResponse response = new PurchaseResponse();
        response.setId(purchase.getId());
        response.setPurchaseNumber(purchase.getPurchaseNumber());
        response.setSupplierId(purchase.getSupplier().getId());
        response.setSupplierName(purchase.getSupplier().getName());
        response.setPurchaseDate(purchase.getPurchaseDate());
        response.setSubtotal(purchase.getSubtotal());
        response.setTaxAmount(purchase.getTaxAmount());
        response.setTotalAmount(purchase.getTotalAmount());
        response.setDiscountAmount(purchase.getDiscountAmount());
        response.setPaymentStatus(purchase.getPaymentStatus().name());
        response.setNotes(purchase.getNotes());
        response.setCreatedBy(purchase.getCreatedBy());
        response.setIsActive(purchase.getIsActive());
        response.setCreatedAt(purchase.getCreatedAt());
        response.setUpdatedAt(purchase.getUpdatedAt());

        if (purchase.getItems() != null) {
            List<PurchaseItemResponse> itemResponses = purchase.getItems().stream()
                .map(this::mapItemToResponse)
                .collect(Collectors.toList());
            response.setItems(itemResponses);
        }

        return response;
    }

    private PurchaseItemResponse mapItemToResponse(PurchaseItem item) {
        PurchaseItemResponse response = new PurchaseItemResponse();
        response.setId(item.getId());
        response.setProductId(item.getProduct().getId());
        response.setProductName(item.getProduct().getName());
        response.setQuantity(item.getQuantity());
        response.setUnitCost(item.getUnitCost());
        response.setTotalCost(item.getTotalCost());
        return response;
    }

    private void processStockIncrease(Purchase purchase, Long userId) {
        for (PurchaseItem item : purchase.getItems()) {
            Product product = item.getProduct();
            
            int oldStock = product.getStockQuantity();
            int newStock = oldStock + item.getQuantity();
            product.setStockQuantity(newStock);
            productRepository.save(product);

            // Create stock movement record
            StockMovement movement = new StockMovement();
            movement.setProduct(product);
            movement.setMovementType(StockMovement.MovementType.IN);
            movement.setQuantity(item.getQuantity());
            movement.setReferenceType(StockMovement.ReferenceType.PURCHASE);
            movement.setReferenceId(purchase.getId());
            movement.setDescription("Stock increased from purchase: " + purchase.getPurchaseNumber());
            User user = userRepository.findById(userId).orElse(null);
            movement.setCreatedBy(user);
            movement.setMovementDate(LocalDateTime.now());
            stockMovementRepository.save(movement);
        }
    }

    private String generatePurchaseNumber() {

        String prefix = "PO-";
        String datePart = LocalDate.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        String todayPrefix = prefix + datePart;

        Purchase lastPurchase = purchaseRepository
                .findTopByPurchaseNumberStartingWithOrderByIdDesc(todayPrefix)
                .orElse(null);

        int sequenceNumber = 1;

        if (lastPurchase != null) {

            String lastPurchaseNumber = lastPurchase.getPurchaseNumber();

            sequenceNumber = Integer.parseInt(
                    lastPurchaseNumber.substring(lastPurchaseNumber.lastIndexOf("-") + 1)
            ) + 1;
        }

        return todayPrefix + "-" + String.format("%04d", sequenceNumber);
    }

    public PurchaseResponse updatePaymentStatus(
            Long purchaseId,
            String paymentStatus,
            Long userId
    ) {

        Purchase purchase = purchaseRepository.findById(purchaseId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Purchase not found: " + purchaseId));

        if (!purchase.getIsActive() || purchase.getDeletedAt() != null) {
            throw new ResourceNotFoundException(
                    "Purchase not found: " + purchaseId);
        }

        String oldStatus = purchase.getPaymentStatus().name();

        try {

            purchase.setPaymentStatus(
                    Purchase.PaymentStatus.valueOf(
                            paymentStatus.toUpperCase()
                    )
            );

        } catch (IllegalArgumentException ex) {

            throw new BusinessException(
                    "Invalid payment status. Allowed values: PENDING, PARTIAL, PAID"
            );

        }

        Purchase savedPurchase = purchaseRepository.save(purchase);

        auditLogService.logAction(
                userId,
                "PURCHASE_PAYMENT_STATUS_UPDATE",
                "Updated payment status for purchase: "
                        + savedPurchase.getPurchaseNumber(),
                "Purchase",
                savedPurchase.getId(),
                oldStatus,
                savedPurchase.getPaymentStatus().name()
        );

        return mapToResponse(savedPurchase);
    }


    public void deletePurchase(Long id, Long userId) {
        Purchase purchase = purchaseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found: " + id));
        
        purchase.setDeletedAt(LocalDateTime.now());
        purchase.setIsActive(false);
        purchaseRepository.save(purchase);

        auditLogService.logAction(userId, "PURCHASE_DELETE", 
            "Purchase deleted: " + purchase.getPurchaseNumber(), 
            "Purchase", purchase.getId(), purchase.toString(), null);
    }

    @Transactional
    public PurchaseResponse updatePurchase(Long purchaseId,PurchaseCreateRequest request,Long userId) {

        Purchase purchase = purchaseRepository.findById(purchaseId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Purchase not found: " + purchaseId));

        if (!purchase.getIsActive() || purchase.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Purchase not found: " + purchaseId);
        }

        Supplier supplier = supplierRepository.findById(request.getSupplierId())
                .orElseThrow(() ->
                        new ResourceNotFoundException("Supplier not found: " + request.getSupplierId()));

        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new BusinessException("Purchase must have at least one item");
        }

        // Reverse previous stock
        for (PurchaseItem oldItem : purchase.getItems()) {

            Product product = oldItem.getProduct();

            int newStock = product.getStockQuantity() - oldItem.getQuantity();

            if (newStock < 0) {
                throw new BusinessException(
                        "Cannot update purchase because it would make stock negative for product: "
                                + product.getName()
                );
            }

            product.setStockQuantity(newStock);

            productRepository.save(product);
        }

        // Update purchase header
        purchase.setSupplier(supplier);
        purchase.setPurchaseDate(
                LocalDate.parse(request.getPurchaseDate(), DateTimeFormatter.ISO_LOCAL_DATE)
        );
        purchase.setNotes(request.getNotes());

        // Remove old purchase items
        for (PurchaseItem item : purchase.getItems()) {
            item.setPurchase(null);
        }
        purchase.getItems().clear();

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal taxAmount = BigDecimal.ZERO;

        // Create new purchase items
        for (PurchaseCreateRequest.PurchaseItemRequest itemRequest : request.getItems()) {

            Product product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() ->
                            new ResourceNotFoundException(
                                    "Product not found: " + itemRequest.getProductId()));

            PurchaseItem item = new PurchaseItem();

            item.setPurchase(purchase);
            item.setProduct(product);
            item.setQuantity(itemRequest.getQuantity());
            item.setUnitCost(itemRequest.getUnitCost());

            BigDecimal totalCost = itemRequest.getUnitCost()
                    .multiply(BigDecimal.valueOf(itemRequest.getQuantity()));

            item.setTotalCost(totalCost);

            purchase.getItems().add(item);

            subtotal = subtotal.add(totalCost);
        }

        purchase.setSubtotal(subtotal);
        purchase.setTaxAmount(taxAmount);
        purchase.setTotalAmount(subtotal.add(taxAmount));

        Purchase savedPurchase = purchaseRepository.save(purchase);

        // Apply new stock
        processStockIncrease(savedPurchase, userId);

        auditLogService.logAction(
                userId,
                "PURCHASE_UPDATE",
                "Purchase updated: " + savedPurchase.getPurchaseNumber(),
                "Purchase",
                savedPurchase.getId(),
                null,
                savedPurchase.toString()
        );

        return mapToResponse(savedPurchase);
    }
}
