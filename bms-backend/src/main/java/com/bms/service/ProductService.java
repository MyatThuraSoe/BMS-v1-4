package com.bms.service;

import com.bms.dto.response.ImportResultDto;


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
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.CSVWriter;
import com.opencsv.ICSVParser;
import com.opencsv.bean.CsvBindByName;
import com.opencsv.bean.CsvToBean;
import com.opencsv.bean.CsvToBeanBuilder;
import com.opencsv.exceptions.CsvException;


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
        return getAllProducts(null, pageable);
    }

    public Page<ProductResponse> getAllProducts(Long categoryId, Pageable pageable) {
        Page<Product> products;
        if (categoryId != null) {
            products = productRepository.findByCategoryIdAndIsActiveTrue(categoryId, pageable);
        } else {
            products = productRepository.findActiveProducts(pageable);
        }
        return products.map(this::convertToResponse);
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

        String barcode = request.getBarcode();
        if (barcode == null || barcode.trim().isEmpty()) {
            barcode = generateUniqueBarcode();
        } else if (productRepository.existsByBarcode(barcode)) {
            throw new BusinessException("Product with barcode '" + barcode + "' already exists");
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
        product.setBarcode(barcode);

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

        String barcode = request.getBarcode();
        if (barcode == null || barcode.trim().isEmpty()) {
            // if client sends blank, keep existing barcode if present, otherwise generate
            if (product.getBarcode() == null || product.getBarcode().trim().isEmpty()) {
                barcode = generateUniqueBarcode();
            } else {
                barcode = product.getBarcode();
            }
        } else {
            if (!barcode.equals(product.getBarcode()) && productRepository.existsByBarcode(barcode)) {
                throw new BusinessException("Product with barcode '" + barcode + "' already exists");
            }
        }

        product.setSku(request.getSku());
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setUnitPrice(request.getUnitPrice());
        product.setCostPrice(request.getCostPrice());
        product.setTaxRate(request.getTaxRate() != null ? request.getTaxRate() : BigDecimal.ZERO);
        product.setMinStockLevel(request.getMinStockLevel() != null ? request.getMinStockLevel() : 0);
        product.setBarcode(barcode);

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

    /**
     * Doc4 §2.3 CSV import.
     * Expected CSV columns (header): sku, name, description, categoryName, unitPrice, costPrice, stockQuantity, minStockLevel, barcode
     */
    public ImportResultDto importProductsFromCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("CSV file is required");
        }

        ImportResultDto result = new ImportResultDto();
        List<ImportResultDto.RowError> errors = new ArrayList<>();

        try (Reader reader = new java.io.InputStreamReader(file.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)) {
            ICSVParser parser = new CSVParserBuilder()
                    .withSeparator(',')
                    .withIgnoreQuotations(false)
                    .build();

            CSVReader csvReader = new CSVReaderBuilder(reader)
                    .withCSVParser(parser)
                    .build();

            // Read header first
            String[] header = csvReader.readNext();
            if (header == null) {
                throw new BusinessException("CSV is empty");
            }

            java.util.Map<String, Integer> headerIndex = new java.util.HashMap<>();
            for (int i = 0; i < header.length; i++) {
                if (header[i] != null) {
                    headerIndex.put(header[i].trim(), i);
                }
            }

            String[] required = new String[]{
                    "sku", "name", "description", "categoryName",
                    "unitPrice", "costPrice", "stockQuantity", "minStockLevel", "barcode"
            };
            for (String col : required) {
                if (!headerIndex.containsKey(col)) {
                    throw new BusinessException("Missing required CSV column: " + col);
                }
            }

            int rowNumber = 1; // header is row 1
            String[] row;
            int totalRows = 0;
            int successCount = 0;
            int updatedCount = 0;
            int createdCount = 0;

            while ((row = csvReader.readNext()) != null) {
                rowNumber++;
                totalRows++;

                try {
                    String sku = getCell(row, headerIndex, "sku");
                    String name = getCell(row, headerIndex, "name");
                    String description = getCell(row, headerIndex, "description");
                    String categoryName = getCell(row, headerIndex, "categoryName");
                    String barcode = getCell(row, headerIndex, "barcode");

                    if (sku == null || sku.isBlank()) {
                        throw new BusinessException("SKU is required");
                    }
                    if (name == null || name.isBlank()) {
                        throw new BusinessException("Name is required");
                    }
                    if (unitPriceMissing(row, headerIndex)) {
                        throw new BusinessException("Unit price is required");
                    }

                    BigDecimal unitPrice = parseBigDecimal(getCell(row, headerIndex, "unitPrice"), "Unit price");
                    BigDecimal costPrice = parseBigDecimal(getCell(row, headerIndex, "costPrice"), "Cost price");
                    Integer stockQuantity = parseInteger(getCell(row, headerIndex, "stockQuantity"), "Stock quantity", 0);
                    Integer minStockLevel = parseInteger(getCell(row, headerIndex, "minStockLevel"), "Min stock level", 0);

                    if (barcode == null || barcode.isBlank()) {
                        barcode = null;
                    }

                    Category category = ensureCategoryByName(categoryName);

                    Product product;
                    boolean exists = productRepository.existsBySku(sku);
                    if (exists) {
                        product = productRepository.findBySku(sku)
                                .orElseThrow(() -> new ResourceNotFoundException("Product not found with SKU: " + sku));
                        product.setName(name);
                        product.setDescription(description);
                        product.setCategory(category);
                        product.setUnitPrice(unitPrice);
                        product.setCostPrice(costPrice);
                        product.setStockQuantity(stockQuantity);
                        product.setMinStockLevel(minStockLevel);

                        if (barcode != null) {
                            if (!barcode.equals(product.getBarcode()) && productRepository.existsByBarcode(barcode)) {
                                throw new BusinessException("Product with barcode '" + barcode + "' already exists");
                            }
                            product.setBarcode(barcode);
                        }

                        product = productRepository.save(product);
                        updatedCount++;
                    } else {
                        product = new Product();
                        product.setSku(sku);
                        product.setName(name);
                        product.setDescription(description);
                        product.setCategory(category);
                        product.setUnitPrice(unitPrice);
                        product.setCostPrice(costPrice);
                        product.setStockQuantity(stockQuantity);
                        product.setMinStockLevel(minStockLevel);
                        product.setBarcode(barcode != null ? barcode : generateUniqueBarcode());
                        product = productRepository.save(product);
                        createdCount++;
                    }

                    successCount++;
                } catch (Exception ex) {
                    String message = ex.getMessage() != null ? ex.getMessage() : "Row import failed";
                    ImportResultDto.RowError err = new ImportResultDto.RowError();
                    err.setRow(rowNumber);
                    err.setMessage(message);
                    errors.add(err);
                }
            }

            result.setTotalRows(totalRows);
            result.setSuccessCount(successCount);
            result.setUpdatedCount(updatedCount);
            result.setCreatedCount(createdCount);
            result.setErrors(errors);

            return result;
        } catch (CsvException e) {
            throw new BusinessException("Failed to parse CSV: " + e.getMessage());
        } catch (IOException e) {
            throw new BusinessException("Failed to read CSV: " + e.getMessage());
        }
    }

    public void exportProductsToCsv(Writer writer) {
        try {
            CSVWriter csvWriter = new CSVWriter(writer,
                    ',',
                    com.opencsv.ICSVWriter.DEFAULT_QUOTE_CHARACTER,
                    com.opencsv.ICSVWriter.DEFAULT_ESCAPE_CHARACTER,
                    com.opencsv.CSVWriter.DEFAULT_LINE_END);

            String[] header = new String[]{
                    "sku", "name", "description", "categoryName", "unitPrice", "costPrice",
                    "stockQuantity", "minStockLevel", "barcode"
            };
            csvWriter.writeNext(header);

            // Export all active products (no paging: intended for admin exports)
            List<Product> products = productRepository.findAll();
            for (Product p : products) {
                if (p.getIsActive() == null || !p.getIsActive() || p.getDeletedAt() != null) continue;
                csvWriter.writeNext(new String[]{
                        safe(p.getSku()),
                        safe(p.getName()),
                        safe(p.getDescription()),
                        p.getCategory() != null ? safe(p.getCategory().getName()) : "",
                        p.getUnitPrice() != null ? p.getUnitPrice().toPlainString() : "",
                        p.getCostPrice() != null ? p.getCostPrice().toPlainString() : "",
                        String.valueOf(p.getStockQuantity() != null ? p.getStockQuantity() : 0),
                        String.valueOf(p.getMinStockLevel() != null ? p.getMinStockLevel() : 0),
                        safe(p.getBarcode())
                });
            }

            csvWriter.flush();
        } catch (IOException e) {
            throw new BusinessException("Failed to export products to CSV: " + e.getMessage());
        }
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private String getCell(String[] row, java.util.Map<String, Integer> headerIndex, String col) {
        Integer idx = headerIndex.get(col);
        if (idx == null) return null;
        if (idx < 0 || idx >= row.length) return null;
        String v = row[idx];
        return v != null ? v.trim() : null;
    }

    private boolean unitPriceMissing(String[] row, java.util.Map<String, Integer> headerIndex) {
        String v = getCell(row, headerIndex, "unitPrice");
        return v == null || v.isBlank();
    }

    private BigDecimal parseBigDecimal(String v, String fieldName) {
        if (v == null || v.isBlank()) {
            throw new BusinessException(fieldName + " is required");
        }
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            throw new BusinessException(fieldName + " is invalid");
        }
    }

    private BigDecimal parseBigDecimal(String v, String fieldName, BigDecimal defaultValue) {
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            throw new BusinessException(fieldName + " is invalid");
        }
    }

    private Integer parseInteger(String v, String fieldName, int defaultValue) {
        if (v == null || v.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new BusinessException(fieldName + " is invalid");
        }
    }

    private Category ensureCategoryByName(String categoryName) {
        String normalized = categoryName != null ? categoryName.trim() : null;
        if (normalized == null || normalized.isBlank()) {
            // Requirement recommends auto-create; if blank category is provided treat as error.
            throw new BusinessException("categoryName is required");
        }

        // Case-insensitive lookup: current repo only supports contains. We'll load all active categories (small table) for accuracy.
        List<Category> categories = categoryRepository.findAllActive();
        for (Category c : categories) {
            if (c.getName() != null && c.getName().equalsIgnoreCase(normalized)) {
                return c;
            }
        }

        // Auto-create
        Category created = new Category();
        created.setName(normalized);
        created.setDescription(null);
        created.setIsActive(true);
        return categoryRepository.save(created);
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

    private String generateUniqueBarcode() {
        // Generate a numeric barcode candidate and ensure uniqueness in DB.
        // (Simple v1 generator; can be replaced later with EAN/UPC rules if needed.)
        for (int attempts = 0; attempts < 10_000; attempts++) {
            String candidate = String.valueOf(Math.abs(java.util.UUID.randomUUID().hashCode()));
            candidate = candidate.replaceAll("\\D+", "");
            if (candidate.length() < 12) {
                candidate = String.format("%-12s", candidate).replace(' ', '0') + candidate;
            }
            // take last 12 digits
            if (candidate.length() > 12) {
                candidate = candidate.substring(candidate.length() - 12);
            } else if (candidate.length() < 12) {
                candidate = String.format("%012d", Long.parseLong(candidate));
            }

            if (!productRepository.existsByBarcode(candidate)) {
                return candidate;
            }
        }
        throw new BusinessException("Unable to generate unique barcode. Please try again.");
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }
}
