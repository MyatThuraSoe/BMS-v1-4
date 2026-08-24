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
import java.util.List;
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

    public Page<ProductResponse> searchProducts(String keyword, Pageable pageable) {
        return productRepository.searchActiveProducts(keyword, pageable).map(this::convertToResponse);
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

    public Product createProduct(ProductCreateRequest request) {
        if (productRepository.existsBySkuIgnoreCase(request.getSku())) {
            throw new BusinessException("Product with SKU '" + request.getSku() + "' already exists");
        }

        validateNonNegativeValues(request);

        Product product = new Product();
        product.setSku(request.getSku());
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setUnitPrice(request.getUnitPrice());
        product.setCostPrice(request.getCostPrice());
        product.setTaxRate(request.getTaxRate() != null ? request.getTaxRate() : BigDecimal.ZERO);
        product.setStockQuantity(request.getStockQuantity() != null ? request.getStockQuantity() : 0);
        product.setMinStockLevel(request.getMinStockLevel() != null ? request.getMinStockLevel() : 0);
        product.setUnit(request.getUnit());

        if (request.getCategoryId() != null) {
            Category category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + request.getCategoryId()));
            product.setCategory(category);
        }

        Product savedProduct = productRepository.save(product);
        auditLogService.logAction(getCurrentUserId(), "PRODUCT_CREATE",
                "Product created: " + savedProduct.getName(),
                "Product", savedProduct.getId(), null, savedProduct.toString());

        return savedProduct;
    }

    public Product updateProduct(Long id, ProductCreateRequest request, Long userId) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
        if (!product.getIsActive() || product.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Product not found: " + id);
        }

        String oldValues = product.toString();

        validateNonNegativeValues(request);

if (!product.getSku().equalsIgnoreCase(request.getSku()) && productRepository.existsBySkuIgnoreCase(request.getSku())) {
            throw new BusinessException("Product with SKU '" + request.getSku() + "' already exists");
        }

        BigDecimal oldPrice = product.getUnitPrice();
        BigDecimal newPrice = request.getUnitPrice();
        if (oldPrice != null && newPrice != null && oldPrice.compareTo(newPrice) != 0) {
            ProductPriceHistory priceHistory = new ProductPriceHistory();
            priceHistory.setProduct(product);
            priceHistory.setOldPrice(oldPrice);
            priceHistory.setNewPrice(newPrice);
            User changedBy = userId != null ? userRepository.findById(userId).orElse(null) : null;
            priceHistory.setChangedBy(changedBy);
            priceHistoryRepository.save(priceHistory);
        }

        product.setSku(request.getSku());
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setUnitPrice(newPrice);
        product.setCostPrice(request.getCostPrice());
product.setTaxRate(request.getTaxRate() != null ? request.getTaxRate() : BigDecimal.ZERO);
        product.setMinStockLevel(request.getMinStockLevel() != null ? request.getMinStockLevel() : 0);
        product.setUnit(request.getUnit());

        if (request.getCategoryId() != null) {
            Category category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + request.getCategoryId()));
            product.setCategory(category);
        } else {
            product.setCategory(null);
        }

        Product updatedProduct = productRepository.save(product);
        auditLogService.logAction(userId, "PRODUCT_UPDATE",
                "Product updated: " + updatedProduct.getName(),
                "Product", updatedProduct.getId(), oldValues, updatedProduct.toString());

        return updatedProduct;
    }

    public void deleteProduct(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));

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

        product.setImageData(file.getBytes());
        product.setImageType(com.bms.util.ImageValidationUtil.mimeToExtension(mime));
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

    public void deleteProductImage(Long productId, Long userId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        product.setImageData(null);
        product.setImageType(null);
        productRepository.save(product);

        auditLogService.logAction(userId, "PRODUCT_IMAGE_DELETE",
                "Image deleted for product: " + product.getName(),
                "Product", productId, null, null);
    }

    @Transactional
    public void adjustStock(Long userId, Long productId, Integer quantityChange, String reason) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        int newQuantity = product.getStockQuantity() + quantityChange;
        if (newQuantity < 0) {
            throw new BusinessException("Cannot reduce stock below zero. Current stock: " +
                    product.getStockQuantity() + ", requested change: " + quantityChange);
        }

        String oldStock = String.valueOf(product.getStockQuantity());
        product.setStockQuantity(newQuantity);
        productRepository.save(product);

        StockMovement movement = new StockMovement();
        movement.setProduct(product);
        movement.setMovementType(quantityChange > 0 ?
                StockMovement.MovementType.ADJUSTMENT_IN : StockMovement.MovementType.ADJUSTMENT_OUT);
        movement.setQuantity(Math.abs(quantityChange));
        movement.setReferenceType(StockMovement.ReferenceType.STOCK_ADJUSTMENT);
        movement.setReferenceId(productId);
        movement.setDescription(reason);
        User user = userRepository.findById(userId).orElse(null);
        movement.setCreatedBy(user);
        movement.setMovementDate(LocalDateTime.now());
        stockMovementRepository.save(movement);

        auditLogService.logAction(userId, "STOCK_ADJUSTMENT",
                "Stock adjusted for product: " + product.getName() + " by " + quantityChange,
                "Product", productId, oldStock, String.valueOf(newQuantity));
    }

    public Page<ProductResponse> getLowStockProducts(int threshold, Pageable pageable) {
        return productRepository.findLowStockProducts(pageable).map(this::convertToResponse);
    }

    public List<ProductSupplierHistoryDto> getProductSuppliers(Long productId) {
        productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        List<Object[]> results = purchaseItemRepository.findProductSupplierHistory(productId);
        List<ProductSupplierHistoryDto> dtos = new ArrayList<>();

        for (Object[] row : results) {
            ProductSupplierHistoryDto dto = new ProductSupplierHistoryDto();
            dto.setSupplierId((Long) row[0]);
            dto.setSupplierName((String) row[1]);
            dto.setTimesPurchased((Long) row[2]);
            dto.setMostRecentUnitCost((BigDecimal) row[3]);
            dto.setMostRecentPurchaseDate((java.time.LocalDate) row[4]);
            dto.setTotalQuantityPurchased(row[5] != null ? ((Number) row[5]).intValue() : 0);
            dto.setTotalAmountSpent(row[6] != null ? (BigDecimal) row[6] : BigDecimal.ZERO);
            dtos.add(dto);
        }

        return dtos;
    }

    public List<PriceHistoryDto> getPriceHistory(Long productId) {
        productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        List<ProductPriceHistory> records = priceHistoryRepository.findByProductIdOrderByChangedAtDesc(productId);
        List<PriceHistoryDto> dtos = new ArrayList<>();

        for (ProductPriceHistory record : records) {
            String username = record.getChangedBy() != null ? record.getChangedBy().getUsername() : "unknown";
            dtos.add(new PriceHistoryDto(
                    record.getOldPrice(),
                    record.getNewPrice(),
                    record.getChangedAt(),
                    username
            ));
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

        if (results.isEmpty() || results.get(0)[0] == null) {
            return new ProductSalesSummaryDto(0L, BigDecimal.ZERO, BigDecimal.ZERO, null);
        }

        Object[] row = results.get(0);

        Long totalQty = ((Number) row[0]).longValue();
        BigDecimal totalRevenue = (BigDecimal) row[1];
        BigDecimal totalProfit = (BigDecimal) row[2];

        if (totalRevenue == null) totalRevenue = BigDecimal.ZERO;
        if (totalProfit == null) totalProfit = BigDecimal.ZERO;

        BigDecimal marginPercent = null;
        if (totalRevenue.compareTo(BigDecimal.ZERO) > 0) {
            marginPercent = totalProfit
                    .multiply(BigDecimal.valueOf(100))
                    .divide(totalRevenue, 2, RoundingMode.HALF_UP);
        }

        return new ProductSalesSummaryDto(totalQty, totalRevenue, totalProfit, marginPercent);
    }

    public List<CostHistoryDto> getCostHistory(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        BigDecimal currentSellingPrice = product.getUnitPrice();
        List<Object[]> results = purchaseItemRepository.findProductCostHistory(productId);
        List<CostHistoryDto> dtos = new ArrayList<>();

        for (Object[] row : results) {
            java.time.LocalDate purchaseDate = (java.time.LocalDate) row[0];
            String supplierName = (String) row[1];
            Long supplierId = (Long) row[2];
            Integer quantity = ((Number) row[3]).intValue();
            BigDecimal unitCost = (BigDecimal) row[4];

            BigDecimal impliedMarginPercent = BigDecimal.ZERO;
            if (currentSellingPrice.compareTo(BigDecimal.ZERO) > 0) {
                impliedMarginPercent = currentSellingPrice.subtract(unitCost)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(currentSellingPrice, 2, RoundingMode.HALF_UP);
            }

            CostHistoryDto dto = new CostHistoryDto(purchaseDate, supplierName, supplierId,
                    quantity, unitCost, currentSellingPrice, impliedMarginPercent);
            dtos.add(dto);
        }

        return dtos;
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
        response.setStockQuantity(product.getStockQuantity());
        response.setReservedQuantity(product.getReservedQuantity());
        response.setAvailableQuantity(product.getAvailableQuantity());
        response.setMinStockLevel(product.getMinStockLevel());
        response.setUnit(product.getUnit());
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
}