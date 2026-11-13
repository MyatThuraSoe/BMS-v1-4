package com.bms.service;

import com.bms.dto.request.ProductUpdateRequest;
import com.bms.dto.response.CostHistoryDto;
import com.bms.dto.response.PriceHistoryDto;
import com.bms.dto.response.ProductSupplierHistoryDto;
import com.bms.dto.response.UnifiedPriceHistoryDto;
import com.bms.entity.Product;
import com.bms.entity.ProductPriceHistory;
import com.bms.entity.User;
import com.bms.repository.CategoryRepository;
import com.bms.repository.ProductPriceHistoryRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.PurchaseItemRepository;
import com.bms.repository.SaleItemRepository;
import com.bms.repository.StockMovementRepository;
import com.bms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * P8: cost and price history must answer audit questions. Covers the latest-received
 * supplier cost (not MAX), received-only labelled cost history with resulting WAC,
 * symmetric null->value selling history, audit fields + priceType filter on price
 * history, and stable repository ordering.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductServiceHistoryTest {

    @Mock private ProductRepository productRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private StockMovementRepository stockMovementRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private PurchaseItemRepository purchaseItemRepository;
    @Mock private SaleItemRepository saleItemRepository;
    @Mock private ProductPriceHistoryRepository priceHistoryRepository;
    @Mock private LocationService locationService;

    private ProductService productService;
    private Product product;

    @BeforeEach
    void setUp() {
        productService = new ProductService();
        ReflectionTestUtils.setField(productService, "productRepository", productRepository);
        ReflectionTestUtils.setField(productService, "categoryRepository", categoryRepository);
        ReflectionTestUtils.setField(productService, "stockMovementRepository", stockMovementRepository);
        ReflectionTestUtils.setField(productService, "userRepository", userRepository);
        ReflectionTestUtils.setField(productService, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(productService, "purchaseItemRepository", purchaseItemRepository);
        ReflectionTestUtils.setField(productService, "saleItemRepository", saleItemRepository);
        ReflectionTestUtils.setField(productService, "priceHistoryRepository", priceHistoryRepository);
        ReflectionTestUtils.setField(productService, "locationService", locationService);

        product = new Product();
        product.setId(1L);
        product.setSku("SKU-1");
        product.setName("Widget");
        product.setUnitPrice(new BigDecimal("20.00"));
        product.setCostPrice(new BigDecimal("10.00"));
        product.setStockQuantity(100);
        product.setVersion(3L);
        product.setIsActive(true);

        lenient().when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        lenient().when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(productRepository.existsBySkuIgnoreCase(anyString())).thenReturn(false);
        lenient().when(productRepository.existsByBarcodeIgnoreCase(anyString())).thenReturn(false);
        lenient().when(priceHistoryRepository.save(any(ProductPriceHistory.class))).thenAnswer(inv -> inv.getArgument(0));

        User admin = new User();
        admin.setId(9L);
        admin.setUsername("admin");
        lenient().when(userRepository.findById(anyLong())).thenReturn(Optional.of(admin));
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but was " + actual);
    }

    private static ProductPriceHistory costHistory(Long purchaseId, String newPrice) {
        ProductPriceHistory h = new ProductPriceHistory();
        h.setPriceType(ProductPriceHistory.PriceType.COST);
        h.setSource(ProductPriceHistory.Source.PURCHASE_RECEIPT);
        h.setPurchaseId(purchaseId);
        h.setOldPrice(new BigDecimal("10.00"));
        h.setNewPrice(new BigDecimal(newPrice));
        h.setChangedAt(LocalDateTime.now());
        return h;
    }

    @Test
    void p8_supplierMostRecentUnitCostIsLatestReceivedNotMax() {
        // Later PO is cheaper (10) than the earlier one (20): MAX would wrongly show 20.
        lenient().when(purchaseItemRepository.findProductSupplierHistory(1L)).thenReturn(List.<Object[]>of(
                new Object[]{7L, "ACME", 2L, new BigDecimal("20.00"),
                        LocalDate.of(2026, 2, 1), 30L, new BigDecimal("500.00")}));
        lenient().when(purchaseItemRepository.findReceivedUnitCostsLatestFirst(1L)).thenReturn(List.<Object[]>of(
                new Object[]{7L, new BigDecimal("10.00"), LocalDate.of(2026, 2, 1)},
                new Object[]{7L, new BigDecimal("20.00"), LocalDate.of(2026, 1, 1)}));

        List<ProductSupplierHistoryDto> suppliers = productService.getProductSuppliers(1L);

        assertEquals(1, suppliers.size());
        assertMoney("10.00", suppliers.get(0).getMostRecentUnitCost());
        assertEquals(LocalDate.of(2026, 2, 1), suppliers.get(0).getMostRecentPurchaseDate());
    }

    @Test
    void p8_supplierMostRecentUnitCostAcceptanceTenThenTwenty() {
        // Spec acceptance: two POs (10 then 20) shows 20 as most recent.
        lenient().when(purchaseItemRepository.findProductSupplierHistory(1L)).thenReturn(List.<Object[]>of(
                new Object[]{7L, "ACME", 2L, new BigDecimal("20.00"),
                        LocalDate.of(2026, 2, 1), 30L, new BigDecimal("300.00")}));
        lenient().when(purchaseItemRepository.findReceivedUnitCostsLatestFirst(1L)).thenReturn(List.<Object[]>of(
                new Object[]{7L, new BigDecimal("20.00"), LocalDate.of(2026, 2, 1)},
                new Object[]{7L, new BigDecimal("10.00"), LocalDate.of(2026, 1, 1)}));

        List<ProductSupplierHistoryDto> suppliers = productService.getProductSuppliers(1L);

        assertMoney("20.00", suppliers.get(0).getMostRecentUnitCost());
    }

    @Test
    void p8_costHistoryLabelsCostAndShowsLandedBaseAndResultingWac() {
        lenient().when(priceHistoryRepository.findByProductIdAndPriceTypeOrderByChangedAtDescIdDesc(
                eq(1L), eq(ProductPriceHistory.PriceType.COST)))
                .thenReturn(List.of(costHistory(50L, "11.00")));
        // purchaseDate, supplierName, supplierId, receivedQty, unitCost, landedUnitCost, baseUnitCost, purchaseId
        lenient().when(purchaseItemRepository.findProductCostHistory(1L)).thenReturn(List.<Object[]>of(
                new Object[]{LocalDate.of(2026, 2, 1), "ACME", 7L, 10,
                        new BigDecimal("10.00"), new BigDecimal("12.00"), new BigDecimal("12.00"), 50L}));

        List<CostHistoryDto> history = productService.getCostHistory(1L);

        assertEquals(1, history.size());
        CostHistoryDto dto = history.get(0);
        assertEquals("PURCHASE_UNIT_COST", dto.getCostType());
        assertMoney("10.00", dto.getUnitCost());
        assertMoney("12.00", dto.getLandedUnitCost());
        assertMoney("12.00", dto.getBaseUnitCost());
        assertMoney("11.00", dto.getResultingWac());
        assertEquals(10, dto.getQuantity());
    }

    @Test
    void p8_costHistoryFallsBackWhenLandedColumnsAreNull() {
        lenient().when(priceHistoryRepository.findByProductIdAndPriceTypeOrderByChangedAtDescIdDesc(
                eq(1L), eq(ProductPriceHistory.PriceType.COST))).thenReturn(List.of());
        lenient().when(purchaseItemRepository.findProductCostHistory(1L)).thenReturn(List.<Object[]>of(
                new Object[]{LocalDate.of(2026, 2, 1), "ACME", 7L, 5,
                        new BigDecimal("8.00"), null, null, 51L}));

        CostHistoryDto dto = productService.getCostHistory(1L).get(0);

        assertMoney("8.00", dto.getLandedUnitCost());
        assertMoney("8.00", dto.getBaseUnitCost());
        assertNull(dto.getResultingWac());
    }

    @Test
    void p8_sellingPriceHistoryRecordsNullToValue() {
        product.setUnitPrice(null);
        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setSku("SKU-1");
        request.setName("Widget");
        request.setUnitPrice(new BigDecimal("25.00"));
        request.setVersion(3L);

        productService.updateProduct(1L, request, 9L);

        ArgumentCaptor<ProductPriceHistory> captor = ArgumentCaptor.forClass(ProductPriceHistory.class);
        verify(priceHistoryRepository).save(captor.capture());
        ProductPriceHistory saved = captor.getValue();
        assertEquals(ProductPriceHistory.PriceType.SELLING, saved.getPriceType());
        assertEquals(ProductPriceHistory.Source.SELLING_PRICE_EDIT, saved.getSource());
        assertMoney("0.00", saved.getOldPrice());
        assertMoney("25.00", saved.getNewPrice());
    }

    @Test
    void p8_priceHistoryCarriesAuditFieldsAndFiltersByType() {
        ProductPriceHistory record = costHistory(50L, "11.00");
        record.setReason("Supplier price correction agreed");
        record.setSupplierId(7L);
        record.setStockQtyAtChange(100);
        record.setInventoryValueDelta(new BigDecimal("10.00"));
        record.setChangedBy(userRepository.findById(9L).orElse(null));
        lenient().when(priceHistoryRepository.findByProductIdAndPriceTypeOrderByChangedAtDescIdDesc(
                eq(1L), eq(ProductPriceHistory.PriceType.COST))).thenReturn(List.of(record));

        List<PriceHistoryDto> history = productService.getPriceHistory(1L, ProductPriceHistory.PriceType.COST);

        assertEquals(1, history.size());
        PriceHistoryDto dto = history.get(0);
        assertEquals("COST", dto.getPriceType());
        assertEquals("PURCHASE_RECEIPT", dto.getSource());
        assertEquals("Supplier price correction agreed", dto.getReason());
        assertEquals(7L, dto.getSupplierId());
        assertEquals(50L, dto.getPurchaseId());
        assertEquals(100, dto.getStockQtyAtChange());
        assertMoney("10.00", dto.getInventoryValueDelta());
        assertEquals("admin", dto.getChangedByUsername());
    }

    @Test
    void p8_priceHistoryWithoutFilterReturnsAllTypes() {
        ProductPriceHistory selling = new ProductPriceHistory();
        selling.setPriceType(ProductPriceHistory.PriceType.SELLING);
        selling.setOldPrice(new BigDecimal("20.00"));
        selling.setNewPrice(new BigDecimal("25.00"));
        selling.setChangedAt(LocalDateTime.now());
        lenient().when(priceHistoryRepository.findByProductIdOrderByChangedAtDescIdDesc(1L))
                .thenReturn(List.of(selling));

        List<PriceHistoryDto> history = productService.getPriceHistory(1L, null);

        assertEquals(1, history.size());
        assertEquals("SELLING", history.get(0).getPriceType());
    }

    @Test
    void p8_unifiedHistoryPreservesRepositoryOrder() {
        LocalDateTime sameMoment = LocalDateTime.of(2026, 2, 1, 10, 0);
        ProductPriceHistory first = costHistory(50L, "10.00");
        first.setChangedAt(sameMoment);
        ProductPriceHistory second = costHistory(51L, "20.00");
        second.setChangedAt(sameMoment);
        lenient().when(priceHistoryRepository.findByProductIdOrderByChangedAtDescIdDesc(1L))
                .thenReturn(List.of(first, second));

        List<UnifiedPriceHistoryDto> unified = productService.getUnifiedPriceHistory(1L);

        // Equal timestamps: the (changedAt DESC, id DESC) repository order must survive.
        assertMoney("10.00", unified.get(0).getNewValue());
        assertMoney("20.00", unified.get(1).getNewValue());
    }
}