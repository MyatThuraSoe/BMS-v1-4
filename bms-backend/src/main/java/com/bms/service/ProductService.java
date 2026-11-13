package com.bms.service;

import com.bms.dto.request.ProductCreateRequest;
import com.bms.dto.response.*;
import com.bms.entity.*;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

@Service
@Transactional
public class ProductService {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private PurchaseItemRepository purchaseItemRepository;

    @Autowired
    private SaleItemRepository saleItemRepository;

    @Autowired
    private ProductPriceHistoryRepository priceHistoryRepository;

    @Autowired
    private LocationService locationService;

    public Page<ProductResponse> getAllProducts(Pageable pageable) {
        return getAllProducts(null, null, pageable);
    }

    public Page<ProductResponse> getAllProducts(Long categoryId, Pageable pageable) {
        return getAllProducts(categoryId, null, pageable);
    }

    public Page<ProductResponse> getAllProducts(Long categoryId, String view, Pageable pageable) {
        Page<Product> products;

        switch (view == null ? "" : view) {
            case "most-sold":
                products = productRepository.findMostSoldProducts(pageable);
                break;

            case "least-sold":
                products = productRepository.findLeastSoldProducts(pageable);
                break;

            case "low-stock":
                if (categoryId == null) {
                    products = productRepository.findLowStockProducts(pageable);
                } else {
                    // OPTIMIZED: Database-level filtering instead of in-memory .filter()
                    products = productRepository.findLowStockProductsByCategoryId(categoryId, pageable);
                }
                break;

            default:
                if (categoryId == null) {
                    products = productRepository.findActiveProducts(pageable);
                } else {
                    // OPTIMIZED: Uses @EntityGraph to prevent N+1 queries
                    products = productRepository.findActiveProductsByCategoryId(categoryId, pageable);
                }
                break;
        }

        return products.map(this::convertToResponse);
    }

    public Page<ProductResponse> searchProducts(String keyword, Long categoryId, String view, Pageable pageable) {
        Page<Product> products;
        switch (view == null ? "" : view) {
            case "most-sold":
                products = productRepository.searchMostSoldProducts(keyword, categoryId, pageable);
                break;

            case "least-sold":
                products = productRepository.searchLeastSoldProducts(keyword, categoryId, pageable);
                break;

            case "low-stock":
                products = productRepository.searchLowStockProducts(keyword, categoryId, pageable);
                break;

            default:
                products = productRepository.searchActiveProducts(keyword, categoryId, pageable);
                break;
        }
        return products.map(this::convertToResponse);
    }

    public ProductResponse getProductById(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
        if (!product.getIsActive() || product.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Product not found: " + id);
        }
        return convertToResponse(product);
    }

    public ProductResponse getProductBySku(String sku) {
        Product product = productRepository.findBySkuIgnoreCase(sku)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with SKU: " + sku));
        if (!product.getIsActive() || product.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Product not found with SKU: " + sku);
        }
        return convertToResponse(product);
    }

    public ProductResponse getProductByBarcode(String barcode) {
        Product product = productRepository.findByBarcodeIgnoreCase(barcode)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with barcode: " + barcode));
        if (!product.getIsActive() || product.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Product not found with barcode: " + barcode);
        }
        return convertToResponse(product);
    }

    /**
     * Blank barcodes become null so "empty" and "unset" are the same state, and
     * surrounding whitespace from scanners/paste is dropped before the
     * uniqueness check.
     */
    private String normalizeBarcode(String barcode) {
        if (barcode == null) {
            return null;
        }
        String normalized = barcode.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public Product createProduct(ProductCreateRequest request) {
        if (productRepository.existsBySkuIgnoreCase(request.getSku())) {
            throw new BusinessException("Product with SKU '" + request.getSku() + "' already exists");
        }

        String barcode = normalizeBarcode(request.getBarcode());
        if (barcode != null && productRepository.existsByBarcodeIgnoreCase(barcode)) {
            throw new BusinessException("Product with barcode '" + barcode + "' already exists");
        }

        validateNonNegativeValues(request);
        validateExpiry(request.getHasExpiry(), request.getExpiryDate());
        if (request.getCostPrice() == null) {
            throw new BusinessException("Cost price is required");
        }

        Product product = new Product();
        product.setSku(request.getSku());
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setUnitPrice(request.getUnitPrice());
        product.setCostPrice(request.getCostPrice().setScale(CostCalculationUtils.COST_SCALE, RoundingMode.HALF_UP));
        product.setTaxRate(request.getTaxRate() != null ? request.getTaxRate() : BigDecimal.ZERO);
        product.setStockQuantity(0);
        product.setMinStockLevel(request.getMinStockLevel() != null ? request.getMinStockLevel() : 0);
        product.setUnit(request.getUnit());
        product.setBarcode(barcode);
        product.setHasExpiry(request.getHasExpiry() != null ? request.getHasExpiry() : Boolean.FALSE);
        product.setExpiryDate(request.getHasExpiry() != null && request.getHasExpiry() ? request.getExpiryDate() : null);
        product.setBinLocation(normalizeBinLocation(request.getBinLocation()));
        product.setAbcClass(normalizeAbcClass(request.getAbcClass(), "C"));
        product.setWeight(request.getWeight() != null ? request.getWeight() : BigDecimal.ZERO);

        if (request.getCategoryId() != null) {
            Category category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + request.getCategoryId()));
            product.setCategory(category);
        }

        Product savedProduct = productRepository.save(product);
        Long userId = getCurrentUserId();
        User user = userId != null ? userRepository.findById(userId).orElse(null) : null;
        int openingQuantity = request.getStockQuantity() != null ? request.getStockQuantity() : 0;
        BigDecimal openingValue = savedProduct.getCostPrice().multiply(BigDecimal.valueOf(openingQuantity))
                .setScale(CostCalculationUtils.COST_SCALE, RoundingMode.HALF_UP);
        if (openingQuantity > 0) {
            // The new row remains exclusively locked by its insert until this transaction commits.
            savedProduct.setStockQuantity(openingQuantity);
            productRepository.save(savedProduct);

            StockMovement movement = new StockMovement();
            movement.setProduct(savedProduct);
            movement.setMovementType(StockMovement.MovementType.IN);
            movement.setQuantity(openingQuantity);
            movement.setReferenceType(StockMovement.ReferenceType.OPENING_BALANCE);
            movement.setReferenceId(savedProduct.getId());
            movement.setReasonCode("OPENING_BALANCE");
            movement.setLocation("SHOP");
            movement.setValuationAmount(openingValue);
            movement.setDescription("Product opening balance");
            movement.setCreatedBy(user);
            movement.setMovementDate(LocalDateTime.now());
            stockMovementRepository.save(movement);
        }
        ProductPriceHistory history = new ProductPriceHistory();
        history.setProduct(savedProduct);
        history.setOldPrice(BigDecimal.ZERO);
        history.setNewPrice(savedProduct.getCostPrice());
        history.setPriceType(ProductPriceHistory.PriceType.COST);
        history.setSource(ProductPriceHistory.Source.OPENING_BALANCE);
        history.setReason("Product opening balance");
        history.setStockQtyAtChange(openingQuantity);
        history.setInventoryValueDelta(openingValue);
        history.setChangedBy(user);
        priceHistoryRepository.save(history);

        auditLogService.logAction(userId, "PRODUCT_CREATE",
                "Product created: " + savedProduct.getName(),
                "Product", savedProduct.getId(), null, savedProduct.toString());

        return savedProduct;
    }

    public Product updateProduct(Long id, com.bms.dto.request.ProductUpdateRequest request, Long userId) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
        if (!product.getIsActive() || product.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Product not found: " + id);
        }

        // C4: reject stale writes so a form opened before a receipt cannot roll
        // back the receipt's data. The client must round-trip the version it read.
        assertVersionMatches(product, request.getVersion());

        String oldValues = product.toString() + ", costPrice:" + product.getCostPrice();

        if (request.getUnitPrice() != null && request.getUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("Unit price cannot be negative");
        }
        if (request.getMinStockLevel() != null && request.getMinStockLevel() < 0) {
            throw new BusinessException("Min stock level cannot be negative");
        }
        validateExpiry(request.getHasExpiry(), request.getExpiryDate());

        if (!product.getSku().equalsIgnoreCase(request.getSku()) && productRepository.existsBySkuIgnoreCase(request.getSku())) {
            throw new BusinessException("Product with SKU '" + request.getSku() + "' already exists");
        }

        String barcode = normalizeBarcode(request.getBarcode());
        if (barcode != null && (!barcode.equalsIgnoreCase(product.getBarcode() == null ? "" : product.getBarcode()))
                && productRepository.existsByBarcodeIgnoreCase(barcode)) {
            throw new BusinessException("Product with barcode '" + barcode + "' already exists");
        }

        BigDecimal oldPrice = product.getUnitPrice();
        BigDecimal newPrice = request.getUnitPrice();
        // P8 req 3: symmetric with cost history — record null -> value as well as value -> value.
        boolean sellingChanged = newPrice != null
                && (oldPrice == null || oldPrice.compareTo(newPrice) != 0);
        if (sellingChanged) {
            ProductPriceHistory priceHistory = new ProductPriceHistory();
            priceHistory.setProduct(product);
            priceHistory.setOldPrice(oldPrice != null ? oldPrice : BigDecimal.ZERO);
            priceHistory.setNewPrice(newPrice);
            priceHistory.setPriceType(ProductPriceHistory.PriceType.SELLING);
            priceHistory.setSource(ProductPriceHistory.Source.SELLING_PRICE_EDIT);
            User changedBy = userId != null ? userRepository.findById(userId).orElse(null) : null;
            priceHistory.setChangedBy(changedBy);
            priceHistoryRepository.save(priceHistory);
        }

        // C4: cost and stock are intentionally NOT touched here. Cost moves only
        // through adjustCost; stock only through adjustStock.
        product.setSku(request.getSku());
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setUnitPrice(newPrice);
        product.setMinStockLevel(request.getMinStockLevel() != null ? request.getMinStockLevel() : 0);
        product.setUnit(request.getUnit());
        product.setBarcode(barcode);
        product.setHasExpiry(request.getHasExpiry() != null ? request.getHasExpiry() : Boolean.FALSE);
        product.setExpiryDate(request.getHasExpiry() != null && request.getHasExpiry() ? request.getExpiryDate() : null);
        product.setBinLocation(normalizeBinLocation(request.getBinLocation()));
        product.setAbcClass(normalizeAbcClass(request.getAbcClass(), product.getAbcClass()));
        product.setWeight(request.getWeight() != null ? request.getWeight() : BigDecimal.ZERO);

        if (request.getCategoryId() != null) {
            Category category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + request.getCategoryId()));
            product.setCategory(category);
        } else {
            product.setCategory(null);
        }

        Product updatedProduct = productRepository.save(product);
        String newValues = updatedProduct.toString() + ", costPrice:" + updatedProduct.getCostPrice();
        auditLogService.logAction(userId, "PRODUCT_UPDATE",
                "Product updated: " + updatedProduct.getName(),
            "Product", updatedProduct.getId(), oldValues, newValues);

        return updatedProduct;
    }

    /**
     * C4/D4: explicit, reasoned cost revaluation (ADMIN only at the controller).
     * Runs in one transaction under the product lock: revalues every owned unit,
     * writes a REVALUATION movement carrying the value delta and a COST history
     * row with source MANUAL_ADJUSTMENT, the reason and the user.
     */
    public Product adjustCost(Long id, com.bms.dto.request.CostAdjustmentRequest request, Long userId) {
        Product product = productRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
        if (!product.getIsActive() || product.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Product not found: " + id);
        }

        assertVersionMatches(product, request.getExpectedVersion());

        BigDecimal newCost = request.getNewCost();
        if (newCost == null) {
            throw new BusinessException("New cost is required");
        }
        if (newCost.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("Cost cannot be negative");
        }
        String reason = request.getReason() != null ? request.getReason().trim() : "";
        if (reason.length() < 10) {
            throw new BusinessException("A reason of at least 10 characters is required for cost adjustments");
        }

        newCost = newCost.setScale(CostCalculationUtils.COST_SCALE, RoundingMode.HALF_UP);
        BigDecimal oldCost = product.getCostPrice() != null ? product.getCostPrice() : BigDecimal.ZERO;
        int ownedUnits = locationService.totalOwnedUnits(product);
        BigDecimal valueDelta = newCost.subtract(oldCost)
                .multiply(BigDecimal.valueOf(ownedUnits))
                .setScale(CostCalculationUtils.COST_SCALE, RoundingMode.HALF_UP);

        product.setCostPrice(newCost);
        Product saved = productRepository.save(product);

        User changedBy = userId != null ? userRepository.findById(userId).orElse(null) : null;

        StockMovement movement = new StockMovement();
        movement.setProduct(saved);
        movement.setMovementType(StockMovement.MovementType.REVALUATION);
        movement.setQuantity(0);
        movement.setReferenceType(StockMovement.ReferenceType.STOCK_ADJUSTMENT);
        movement.setReferenceId(id);
        movement.setReasonCode("REVALUATION");
        movement.setValuationAmount(valueDelta);
        movement.setDescription("Cost revaluation " + oldCost + " -> " + newCost + ": " + reason);
        movement.setCreatedBy(changedBy);
        movement.setMovementDate(LocalDateTime.now());
        stockMovementRepository.save(movement);

        ProductPriceHistory history = new ProductPriceHistory();
        history.setProduct(saved);
        history.setOldPrice(oldCost);
        history.setNewPrice(newCost);
        history.setPriceType(ProductPriceHistory.PriceType.COST);
        history.setSource(ProductPriceHistory.Source.MANUAL_ADJUSTMENT);
        history.setReason(reason);
        history.setChangedBy(changedBy);
        history.setStockQtyAtChange(ownedUnits);
        history.setInventoryValueDelta(valueDelta);
        priceHistoryRepository.save(history);

        auditLogService.logAction(userId, "PRODUCT_COST_ADJUSTMENT",
                "Cost adjusted for product: " + saved.getName() + " " + oldCost + " -> " + newCost
                        + " (" + reason + ")",
                "Product", id, oldCost.toString(), newCost.toString());

        return saved;
    }

    private void assertVersionMatches(Product product, Long expectedVersion) {
        if (expectedVersion == null) {
            throw new BusinessException("Product version is required; reload the product and try again");
        }
        if (!expectedVersion.equals(product.getVersion())) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(Product.class, product.getId());
        }
    }

    public void deleteProduct(Long id) {
        Product product = productRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));

        if (locationService.totalOwnedUnits(product) != 0) {
            throw new BusinessException("Cannot delete a product with owned stock; write off or reconcile stock first");
        }
        if (saleItemRepository.existsByProductId(id) || purchaseItemRepository.existsByProductId(id)) {
            throw new BusinessException("Cannot delete a product with sale or purchase history; deactivate it instead");
        }

        // Free the unique SKU so a new product can reuse it after this soft delete.
        product.setSku(com.bms.util.SoftDeleteKeys.release(product.getSku(), product.getId(), 255));
        product.setBarcode(com.bms.util.SoftDeleteKeys.release(product.getBarcode(), product.getId(), 255));
        product.setDeletedAt(LocalDateTime.now());
        product.setIsActive(false);
        productRepository.save(product);

        auditLogService.logAction(getCurrentUserId(), "PRODUCT_DELETE",
                "Product deleted: " + product.getName(),
                "Product", product.getId(), product.toString(), null);
    }

    @Transactional
    public void uploadProductImage(Long productId, MultipartFile file, Long userId) throws IOException {
        // Trust magic bytes, never the client Content-Type / filename extension.
        String mime = com.bms.util.ImageValidationUtil.validateImage(file);

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        // Resize and compress: max 800 px, JPEG 85 % quality, all formats normalised to JPEG.
        byte[] original = file.getBytes();
        com.bms.util.ImageResizeUtil.ResizeResult optimised =
                com.bms.util.ImageResizeUtil.resizeIfLarger(original);

        product.setImageData(optimised.data());
        product.setImageType(optimised.extension());
        product.setThumbnailData(com.bms.util.ImageResizeUtil.createThumbnail(original));
        productRepository.save(product);

        auditLogService.logAction(userId, "PRODUCT_IMAGE_UPLOAD",
                "Image uploaded for product: " + product.getName(),
                "Product", productId, null, null);
    }

    public byte[] getProductImage(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        if (product.getImageData() == null) {
            throw new ResourceNotFoundException("Product " + productId + " has no image");
        }
        return product.getImageData();
    }

    public byte[] getProductThumbnail(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        if (product.getThumbnailData() != null) {
            return product.getThumbnailData();
        }
        // Legacy products (uploaded before thumbnails existed): generate once on first access.
        if (product.getImageData() == null) {
            throw new ResourceNotFoundException("Product " + productId + " has no image");
        }
        byte[] thumbnail = com.bms.util.ImageResizeUtil.createThumbnail(product.getImageData());
        if (thumbnail == null) {
            throw new ResourceNotFoundException("Product " + productId + " image cannot be decoded");
        }
        product.setThumbnailData(thumbnail);
        productRepository.save(product);
        return thumbnail;
    }

    public void deleteProductImage(Long productId, Long userId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        product.setImageData(null);
        product.setImageType(null);
        product.setThumbnailData(null);
        productRepository.save(product);

        auditLogService.logAction(userId, "PRODUCT_IMAGE_DELETE",
                "Image deleted for product: " + product.getName(),
                "Product", productId, null, null);
    }

    @Transactional
    public void adjustStock(Long userId, Long productId, Integer quantityChange, String reason) {
        adjustStock(userId, productId, quantityChange, reason, "COUNT_CORRECTION");
    }

    public void adjustStock(Long userId, Long productId, Integer quantityChange, String reason, String reasonCode) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        if (quantityChange == null || quantityChange == 0) {
            throw new BusinessException("Quantity adjustment must be a non-zero value");
        }
        int newQuantity = product.getStockQuantity() + quantityChange;
        if (newQuantity < 0) {
            throw new BusinessException("Cannot reduce stock below zero. Current stock: " +
                    product.getStockQuantity() + ", requested change: " + quantityChange);
        }

        List<String> allowedReasons = List.of(
                "SPOILAGE_EXPIRY", "THEFT_SHRINKAGE", "DAMAGED_IN_STORE",
                "INTERNAL_CONSUMPTION", "COUNT_CORRECTION");
        String normalizedReason = reasonCode != null ? reasonCode.trim().toUpperCase() : null;
        if (normalizedReason == null || !allowedReasons.contains(normalizedReason)) {
            throw new BusinessException("A valid reason code is required: " + String.join(", ", allowedReasons));
        }

        String oldStock = String.valueOf(product.getStockQuantity());
        product.setStockQuantity(newQuantity);
        productRepository.save(product);

        BigDecimal unitCost = product.getCostPrice() != null ? product.getCostPrice() : BigDecimal.ZERO;
        BigDecimal valuation = unitCost.multiply(BigDecimal.valueOf(quantityChange))
                .setScale(CostCalculationUtils.COST_SCALE, RoundingMode.HALF_UP);

        StockMovement movement = new StockMovement();
        movement.setProduct(product);
        movement.setMovementType(quantityChange > 0 ?
                StockMovement.MovementType.ADJUSTMENT_IN : StockMovement.MovementType.ADJUSTMENT_OUT);
        movement.setQuantity(Math.abs(quantityChange));
        movement.setReferenceType(StockMovement.ReferenceType.STOCK_ADJUSTMENT);
        movement.setReferenceId(productId);
        movement.setReasonCode(normalizedReason);
        movement.setLocation("SHOP");
        movement.setValuationAmount(valuation);
        movement.setDescription((reason != null && !reason.isBlank() ? reason : normalizedReason.replace('_', ' '))
                + " (" + normalizedReason + ")");
        User user = userRepository.findById(userId).orElse(null);
        movement.setCreatedBy(user);
        movement.setMovementDate(LocalDateTime.now());
        stockMovementRepository.save(movement);

        auditLogService.logAction(userId, "STOCK_ADJUSTMENT",
                "Stock adjusted for product: " + product.getName() + " by " + quantityChange
                        + " (" + normalizedReason + ")",
                "Product", productId, oldStock, String.valueOf(newQuantity));
    }

    public Page<ProductResponse> getLowStockProducts(int threshold, Pageable pageable) {
        return productRepository.findLowStockProducts(pageable).map(this::convertToResponse);
    }

    public List<ProductSupplierHistoryDto> getProductSuppliers(Long productId) {
        productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        // P8 req 6: most recent RECEIVED cost per supplier (latest invoice date, id tiebreaker),
        // not MAX(unitCost). Rows arrive latest-first, so the first seen per supplier wins.
        Map<Long, BigDecimal> latestCostBySupplier = new HashMap<>();
        Map<Long, java.time.LocalDate> latestDateBySupplier = new HashMap<>();
        for (Object[] row : purchaseItemRepository.findReceivedUnitCostsLatestFirst(productId)) {
            Long supplierId = (Long) row[0];
            latestCostBySupplier.putIfAbsent(supplierId, (BigDecimal) row[1]);
            latestDateBySupplier.putIfAbsent(supplierId, (java.time.LocalDate) row[2]);
        }

        List<Object[]> results = purchaseItemRepository.findProductSupplierHistory(productId);
        List<ProductSupplierHistoryDto> dtos = new ArrayList<>();

        for (Object[] row : results) {
            ProductSupplierHistoryDto dto = new ProductSupplierHistoryDto();
            Long supplierId = (Long) row[0];
            dto.setSupplierId(supplierId);
            dto.setSupplierName((String) row[1]);
            dto.setTimesPurchased((Long) row[2]);
            dto.setMostRecentUnitCost(latestCostBySupplier.getOrDefault(supplierId, (BigDecimal) row[3]));
            dto.setMostRecentPurchaseDate(latestDateBySupplier.getOrDefault(supplierId, (java.time.LocalDate) row[4]));
            dto.setTotalQuantityPurchased(row[5] != null ? ((Number) row[5]).intValue() : 0);
            dto.setTotalAmountSpent(row[6] != null ? (BigDecimal) row[6] : BigDecimal.ZERO);
            dtos.add(dto);
        }

        return dtos;
    }

    public List<PriceHistoryDto> getPriceHistory(Long productId, ProductPriceHistory.PriceType priceType) {
        productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        List<ProductPriceHistory> records = priceType != null
                ? priceHistoryRepository.findByProductIdAndPriceTypeOrderByChangedAtDescIdDesc(productId, priceType)
                : priceHistoryRepository.findByProductIdOrderByChangedAtDescIdDesc(productId);
        List<PriceHistoryDto> dtos = new ArrayList<>();

        for (ProductPriceHistory record : records) {
            PriceHistoryDto dto = new PriceHistoryDto();
            dto.setOldPrice(record.getOldPrice());
            dto.setNewPrice(record.getNewPrice());
            dto.setChangedAt(record.getChangedAt());
            dto.setChangedByUsername(record.getChangedBy() != null ? record.getChangedBy().getUsername() : "unknown");
            dto.setPriceType(record.getPriceType() != null ? record.getPriceType().name() : null);
            dto.setSource(record.getSource() != null ? record.getSource().name() : null);
            dto.setReason(record.getReason());
            dto.setSupplierId(record.getSupplierId());
            dto.setPurchaseId(record.getPurchaseId());
            dto.setStockQtyAtChange(record.getStockQtyAtChange());
            dto.setInventoryValueDelta(record.getInventoryValueDelta());
            dtos.add(dto);
        }

        return dtos;
    }

    public List<ProductTopCustomerDto> getTopCustomers(Long productId, int limit) {
        productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        Pageable pageable = PageRequest.of(0, limit);
        List<Object[]> results = saleItemRepository.findTopCustomersForProduct(productId, pageable);
        List<ProductTopCustomerDto> dtos = new ArrayList<>();

        for (Object[] row : results) {
            Long customerId = (Long) row[0];
            String firstName = (String) row[1];
            String lastName = (String) row[2];
            String phone = (String) row[3];
            Long totalQty = row[4] != null ? ((Number) row[4]).longValue() : 0L;
            BigDecimal totalSpent = row[5] != null ? (BigDecimal) row[5] : BigDecimal.ZERO;
            LocalDateTime lastPurchase = (LocalDateTime) row[6];
            String customerName = (firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "");
            dtos.add(new ProductTopCustomerDto(customerId, customerName.trim(), phone, totalQty, totalSpent, lastPurchase));
        }

        return dtos;
    }

    public ProductSalesSummaryDto getSalesSummary(Long productId) {
        productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        List<Object[]> results = saleItemRepository.getSalesSummaryForProduct(productId);

        long totalQty = 0L;
        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;

        for (Object[] row : results) {
            BigDecimal saleSubtotal = toBigDecimal(row[0]);
            BigDecimal saleDiscount = toBigDecimal(row[1]);
            BigDecimal lineTotal = toBigDecimal(row[2]);
            int quantity = row[3] != null ? ((Number) row[3]).intValue() : 0;
            int refunded = row[4] != null ? ((Number) row[4]).intValue() : 0;
            BigDecimal costAtSale = toBigDecimal(row[5]);

            int quantitySold = Math.max(0, quantity - refunded);
            if (quantitySold <= 0) {
                continue;
            }

            totalQty += quantitySold;
            totalRevenue = totalRevenue.add(SaleDiscountUtils.netLineRevenue(
                    lineTotal, saleSubtotal, saleDiscount, quantity, quantitySold));
            totalCost = totalCost.add(costAtSale.multiply(BigDecimal.valueOf(quantitySold)));
        }

        totalRevenue = totalRevenue.setScale(2, RoundingMode.HALF_UP);
        totalCost = totalCost.setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalProfit = totalRevenue.subtract(totalCost);

        BigDecimal marginPercent = null;
        if (totalRevenue.compareTo(BigDecimal.ZERO) > 0) {
            marginPercent = totalProfit
                    .multiply(BigDecimal.valueOf(100))
                    .divide(totalRevenue, 2, RoundingMode.HALF_UP);
        }

        return new ProductSalesSummaryDto(totalQty, totalRevenue, totalProfit, marginPercent);
    }

    /**
     * SQLite/JDBC may hand numeric columns back as Integer/Long/BigDecimal
     * depending on stored affinity, so normalise before arithmetic.
     */
    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        return new BigDecimal(value.toString());
    }

    /**
     * Day-of-week x hour-of-day sales heat map for one product.
     *
     * <p>Only the requested window is scanned - callers must not prefetch the
     * longer windows (the POS database can be busy), so each period is loaded
     * on demand from the product page. Rows arrive one-per-sale and are
     * bucketed here in Java rather than in SQL, because day-of-week / hour
     * extraction is not portable across H2, MySQL and SQLite.
     */
    public ProductSaleHeatmapDto getProductSaleHeatmap(Long productId, String period) {
        productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDateTime fromDateTime;
        java.time.LocalDate fromDate;
        switch (period) {
            case "7d":
                fromDate = today.minusDays(6);
                fromDateTime = fromDate.atStartOfDay();
                break;
            case "1m":
                fromDate = today.minusMonths(1).plusDays(1);
                fromDateTime = fromDate.atStartOfDay();
                break;
            case "1y":
                fromDate = today.minusYears(1).plusDays(1);
                fromDateTime = fromDate.atStartOfDay();
                break;
            case "all":
                fromDate = null;
                fromDateTime = null;
                break;
            default:
                throw new BusinessException("Unknown heatmap period: " + period);
        }

        List<Object[]> rows = saleItemRepository.findSalesTimelineForProduct(productId, fromDateTime);

        java.util.Map<String, ProductSaleHeatmapDto.Cell> cells = new java.util.LinkedHashMap<>();
        long totalQuantity = 0L;
        long transactionCount = 0L;
        BigDecimal totalRevenue = BigDecimal.ZERO;

        for (Object[] row : rows) {
            LocalDateTime saleDate = row[1] != null ? (LocalDateTime) row[1] : null;
            if (saleDate == null) {
                continue;
            }
            int quantity = row[2] != null ? ((Number) row[2]).intValue() : 0;
            int refunded = row[3] != null ? ((Number) row[3]).intValue() : 0;
            int quantitySold = Math.max(0, quantity - refunded);
            BigDecimal lineTotal = toBigDecimal(row[4]);
            BigDecimal saleSubtotal = toBigDecimal(row[5]);
            BigDecimal saleDiscount = toBigDecimal(row[6]);
            BigDecimal revenue = SaleDiscountUtils.netLineRevenue(
                    lineTotal, saleSubtotal, saleDiscount, quantity, quantitySold);

            int dayOfWeek = saleDate.getDayOfWeek().getValue(); // 1=Mon..7=Sun
            int hourOfDay = saleDate.getHour();
            String key = dayOfWeek + "_" + hourOfDay;

            ProductSaleHeatmapDto.Cell cell = cells.computeIfAbsent(key, k -> {
                ProductSaleHeatmapDto.Cell c = new ProductSaleHeatmapDto.Cell();
                c.setDayOfWeek(dayOfWeek);
                c.setHourOfDay(hourOfDay);
                return c;
            });
            cell.setQuantity(cell.getQuantity() + quantitySold);
            cell.setRevenue(cell.getRevenue().add(revenue));
            cell.setTransactionCount(cell.getTransactionCount() + 1);

            totalQuantity += quantitySold;
            transactionCount += 1;
            totalRevenue = totalRevenue.add(revenue);
        }

        ProductSaleHeatmapDto dto = new ProductSaleHeatmapDto();
        dto.setPeriod(period);
        dto.setFromDate(fromDate);
        dto.setToDate(today);
        dto.setTotalQuantity(totalQuantity);
        dto.setTransactionCount(transactionCount);
        dto.setTotalRevenue(totalRevenue);
        dto.setCells(new ArrayList<>(cells.values()));
        return dto;
    }

    public List<CostHistoryDto> getCostHistory(Long productId) {
        productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        // P8 req 7: resulting WAC after each purchase's receipt, from COST history keyed by
        // purchaseId. Rows arrive latest-first, so the first seen per purchase is the result.
        Map<Long, BigDecimal> wacByPurchase = new HashMap<>();
        for (ProductPriceHistory record : priceHistoryRepository
                .findByProductIdAndPriceTypeOrderByChangedAtDescIdDesc(productId, ProductPriceHistory.PriceType.COST)) {
            if (record.getPurchaseId() != null) {
                wacByPurchase.putIfAbsent(record.getPurchaseId(), record.getNewPrice());
            }
        }

        List<Object[]> results = purchaseItemRepository.findProductCostHistory(productId);
        List<CostHistoryDto> dtos = new ArrayList<>();

        for (Object[] row : results) {
            java.time.LocalDate purchaseDate = (java.time.LocalDate) row[0];
            String supplierName = (String) row[1];
            Long supplierId = (Long) row[2];
            Integer quantity = row[3] != null ? ((Number) row[3]).intValue() : 0;
            BigDecimal unitCost = (BigDecimal) row[4];
            BigDecimal landedUnitCost = row[5] != null ? (BigDecimal) row[5] : unitCost;
            BigDecimal baseUnitCost = row[6] != null ? (BigDecimal) row[6] : landedUnitCost;
            Long purchaseId = (Long) row[7];

            CostHistoryDto dto = new CostHistoryDto(purchaseDate, supplierName, supplierId,
                    quantity, unitCost, landedUnitCost, baseUnitCost,
                    "PURCHASE_UNIT_COST", wacByPurchase.get(purchaseId));
            dtos.add(dto);
        }

        return dtos;
    }

    public List<UnifiedPriceHistoryDto> getUnifiedPriceHistory(Long productId) {
        productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        List<UnifiedPriceHistoryDto> unifiedHistory = new ArrayList<>();

        List<ProductPriceHistory> priceChanges =
            priceHistoryRepository.findByProductIdOrderByChangedAtDescIdDesc(productId);
        for (ProductPriceHistory record : priceChanges) {
            boolean isCostChange = record.getPriceType() == ProductPriceHistory.PriceType.COST;

            unifiedHistory.add(new UnifiedPriceHistoryDto(
                record.getChangedAt(),
                isCostChange ? UnifiedPriceHistoryDto.ChangeType.COST
                    : UnifiedPriceHistoryDto.ChangeType.SELLING,
                record.getOldPrice(),
                record.getNewPrice(),
                record.getChangedBy() != null ? record.getChangedBy().getUsername() : "unknown",
                    record.getPurchaseSupplierName(),
                    record.getPurchaseQuantity(),
                    record.getPurchaseUnitPrice()
            ));
        }

        // P8 req 9: repository already orders by (changedAt DESC, id DESC); re-sorting on
        // changedAt alone would discard the stable id tiebreaker.
        return unifiedHistory;
    }


    private ProductResponse convertToResponse(Product product) {
        ProductResponse response = new ProductResponse();
        response.setId(product.getId());
        response.setSku(product.getSku());
        response.setName(product.getName());
        response.setDescription(product.getDescription());
        response.setUnitPrice(product.getUnitPrice());
        response.setCostPrice(product.getCostPrice());
        response.setTaxRate(product.getTaxRate());
        response.setVersion(product.getVersion());
        response.setStockQuantity(product.getStockQuantity());
        response.setReservedQuantity(product.getReservedQuantity());
        response.setAvailableQuantity(product.getAvailableQuantity());
        response.setMinStockLevel(product.getMinStockLevel());
        response.setUnit(product.getUnit());
        response.setBarcode(product.getBarcode());
        response.setHasExpiry(product.getHasExpiry());
        response.setExpiryDate(product.getExpiryDate());
        response.setBinLocation(product.getBinLocation());
        response.setAbcClass(product.getAbcClass());
        response.setWeight(product.getWeight());
        response.setIsActive(product.getIsActive());
        response.setCreatedAt(product.getCreatedAt());
        response.setUpdatedAt(product.getUpdatedAt());

        if (product.getCategory() != null) {
            response.setCategoryId(product.getCategory().getId());
            response.setCategoryName(product.getCategory().getName());
        }

        // Safe check: because of @Basic(fetch = FetchType.LAZY), this won't load the whole byte array
        response.setHasImage(product.getImageData() != null);

        return response;
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserDetails) {
            String username = ((UserDetails) auth.getPrincipal()).getUsername();
            return userRepository.findByUsername(username).map(User::getId).orElse(null);
        }
        return null;
    }

    private String normalizeBinLocation(String binLocation) {
        return binLocation != null && !binLocation.isBlank() ? binLocation.trim() : null;
    }

    private String normalizeAbcClass(String abcClass, String fallback) {
        if (abcClass == null || abcClass.isBlank()) {
            return fallback != null ? fallback : "C";
        }
        String normalized = abcClass.trim().toUpperCase();
        if (!normalized.matches("[ABC]")) {
            throw new BusinessException("ABC class must be A, B or C");
        }
        return normalized;
    }

    private void validateNonNegativeValues(ProductCreateRequest request) {
        if (request.getUnitPrice() != null && request.getUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("Unit price cannot be negative");
        }
        if (request.getCostPrice() != null && request.getCostPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("Cost price cannot be negative");
        }
        if (request.getTaxRate() != null && request.getTaxRate().compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("Tax rate cannot be negative");
        }
        if (request.getStockQuantity() != null && request.getStockQuantity() < 0) {
            throw new BusinessException("Stock quantity cannot be negative");
        }
        if (request.getMinStockLevel() != null && request.getMinStockLevel() < 0) {
            throw new BusinessException("Min stock level cannot be negative");
        }
    }

    /**
     * Expiry is optional, but a product that tracks expiry must carry a date so
     * the expiring-soon report and the POS warning have something to show.
     */
    private void validateExpiry(Boolean hasExpiry, java.time.LocalDate expiryDate) {
        if (Boolean.TRUE.equals(hasExpiry) && expiryDate == null) {
            throw new BusinessException("Expiry date is required when 'has expiry' is enabled");
        }
    }
}