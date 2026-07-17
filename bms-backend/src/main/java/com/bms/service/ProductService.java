package com.bms.service;

import com.bms.dto.request.ProductCreateRequest;
import com.bms.dto.response.ProductResponse;
import com.bms.entity.Category;
import com.bms.entity.Product;
import com.bms.entity.ProductImage;
import com.bms.entity.StockMovement;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.CategoryRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.StockMovementRepository;
import com.bms.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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

    public Page<ProductResponse> getAllProducts(Pageable pageable) {
        return productRepository.findActiveProducts(pageable)
                .map(this::convertToResponse);
    }

    public Page<ProductResponse> searchProducts(String keyword, Pageable pageable) {
        return productRepository.searchActiveProducts(keyword, pageable)
                .map(this::convertToResponse);
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
        Product product = productRepository.findBySku(sku)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with SKU: " + sku));
        if (!product.getIsActive() || product.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Product not found with SKU: " + sku);
        }
        return convertToResponse(product);
    }

    public ProductResponse getProductByBarcode(String barcode) {
        Product product = productRepository.findByBarcode(barcode)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with barcode: " + barcode));
        if (!product.getIsActive() || product.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Product not found with barcode: " + barcode);
        }
        return convertToResponse(product);
    }

    public Product createProduct(ProductCreateRequest request) {
        // Check if SKU already exists
        if (productRepository.existsBySku(request.getSku())) {
            throw new BusinessException("Product with SKU '" + request.getSku() + "' already exists");
        }

        // Check if barcode already exists
        if (request.getBarcode() != null && !request.getBarcode().isEmpty() 
                && productRepository.existsByBarcode(request.getBarcode())) {
            throw new BusinessException("Product with barcode '" + request.getBarcode() + "' already exists");
        }

        Product product = new Product();
        product.setSku(request.getSku());
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setUnitPrice(request.getUnitPrice());
        product.setCostPrice(request.getCostPrice());
        product.setTaxRate(request.getTaxRate() != null ? request.getTaxRate() : BigDecimal.ZERO);
        product.setStockQuantity(request.getStockQuantity() != null ? request.getStockQuantity() : 0);
        product.setMinStockLevel(request.getMinStockLevel() != null ? request.getMinStockLevel() : 0);
        product.setBarcode(request.getBarcode());

        if (request.getCategoryId() != null) {
            Category category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + request.getCategoryId()));
            product.setCategory(category);
        }

        Product savedProduct = productRepository.save(product);

        // Log the product creation
        auditLogService.logAction(null, "PRODUCT_CREATE", 
            "Product created: " + savedProduct.getName(), 
            "Product", savedProduct.getId(), null, savedProduct.toString());

        return savedProduct;
    }

    public Product updateProduct(Long id, ProductCreateRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
        if (!product.getIsActive() || product.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Product not found: " + id);
        }

        String oldValues = product.toString();

        // Check if new SKU conflicts with another product
        if (!product.getSku().equals(request.getSku()) && productRepository.existsBySku(request.getSku())) {
            throw new BusinessException("Product with SKU '" + request.getSku() + "' already exists");
        }

        // Check if new barcode conflicts with another product
        if (request.getBarcode() != null && !request.getBarcode().isEmpty() 
                && !product.getBarcode().equals(request.getBarcode())
                && productRepository.existsByBarcode(request.getBarcode())) {
            throw new BusinessException("Product with barcode '" + request.getBarcode() + "' already exists");
        }

        product.setSku(request.getSku());
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setUnitPrice(request.getUnitPrice());
        product.setCostPrice(request.getCostPrice());
        product.setTaxRate(request.getTaxRate() != null ? request.getTaxRate() : BigDecimal.ZERO);
        product.setMinStockLevel(request.getMinStockLevel() != null ? request.getMinStockLevel() : 0);
        product.setBarcode(request.getBarcode());

        if (request.getCategoryId() != null) {
            Category category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + request.getCategoryId()));
            product.setCategory(category);
        } else {
            product.setCategory(null);
        }

        Product updatedProduct = productRepository.save(product);

        // Log the product update
        auditLogService.logAction(null, "PRODUCT_UPDATE", 
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

        // Log the product deletion
        auditLogService.logAction(null, "PRODUCT_DELETE", 
            "Product deleted: " + product.getName(), 
            "Product", product.getId(), product.toString(), null);
    }

    @Transactional
    public void uploadProductImage(Long productId, MultipartFile file) throws IOException {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        product.setImageData(file.getBytes());
        product.setImageType(getFileExtension(file.getOriginalFilename()));
        productRepository.save(product);

        auditLogService.logAction(null, "PRODUCT_IMAGE_UPLOAD",
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

    public void deleteProductImage(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        product.setImageData(null);
        product.setImageType(null);
        productRepository.save(product);

        auditLogService.logAction(null, "PRODUCT_IMAGE_DELETE",
                "Image deleted for product: " + product.getName(),
                "Product", productId, null, null);
    }

    @Transactional
    public void adjustStock(Long userId, Long productId, Integer quantityChange, String reason) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        int newQuantity = product.getStockQuantity() + quantityChange;
        if (newQuantity < 0) {
            throw new BusinessException("Cannot reduce stock below zero. Current stock: " + 
                product.getStockQuantity() + ", requested change: " + quantityChange);
        }

        String oldStock = String.valueOf(product.getStockQuantity());
        product.setStockQuantity(newQuantity);
        productRepository.save(product);

        // Create stock movement record
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

        // Log the stock adjustment
        auditLogService.logAction(userId, "STOCK_ADJUSTMENT", 
            "Stock adjusted for product: " + product.getName() + " by " + quantityChange, 
            "Product", productId, oldStock, String.valueOf(newQuantity));
    }

    public Page<ProductResponse> getLowStockProducts(int threshold, Pageable pageable) {
        // threshold is intentionally ignored: ProductRepository already uses each product's minStockLevel.
        return productRepository.findLowStockProducts(pageable)
                .map(this::convertToResponse);
    }



    public static class ImageOrderRequest {
        private Long imageId;
        private Integer displayOrder;
        private Boolean isPrimary;
        
        public Long getImageId() { return imageId; }
        public void setImageId(Long imageId) { this.imageId = imageId; }
        public Integer getDisplayOrder() { return displayOrder; }
        public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }
        public Boolean getIsPrimary() { return isPrimary; }
        public void setIsPrimary(Boolean isPrimary) { this.isPrimary = isPrimary; }
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
        response.setMinStockLevel(product.getMinStockLevel());
        response.setBarcode(product.getBarcode());
        response.setIsActive(product.getIsActive());
        response.setCreatedAt(product.getCreatedAt());
        response.setUpdatedAt(product.getUpdatedAt());

        if (product.getCategory() != null) {
            response.setCategoryId(product.getCategory().getId());
            response.setCategoryName(product.getCategory().getName());
        }

        // Check if product has images
        response.setHasImage(product.getImageData() != null);

        return response;
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }
}
