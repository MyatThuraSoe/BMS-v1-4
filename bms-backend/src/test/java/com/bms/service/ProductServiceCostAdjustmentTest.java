package com.bms.service;

import com.bms.dto.request.CostAdjustmentRequest;
import com.bms.dto.request.ProductCreateRequest;
import com.bms.dto.request.ProductUpdateRequest;
import com.bms.entity.Product;
import com.bms.entity.ProductPriceHistory;
import com.bms.entity.StockMovement;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * C4: cost edits must be explicit, reasoned and safe. Cost no longer rides on
 * the product update path; a dedicated ADMIN-only, version-gated, reasoned
 * cost-adjustment revalues all owned units and leaves an audit trail.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductServiceCostAdjustmentTest {

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
        lenient().when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));
        lenient().when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(productRepository.existsBySkuIgnoreCase(anyString())).thenReturn(false);
        lenient().when(productRepository.existsByBarcodeIgnoreCase(anyString())).thenReturn(false);
        lenient().when(locationService.totalOwnedUnits(product)).thenReturn(100);
        lenient().when(stockMovementRepository.save(any(StockMovement.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(priceHistoryRepository.save(any(ProductPriceHistory.class))).thenAnswer(inv -> inv.getArgument(0));

        User admin = new User();
        admin.setId(9L);
        admin.setUsername("admin");
        lenient().when(userRepository.findById(anyLong())).thenReturn(Optional.of(admin));
    }

    private CostAdjustmentRequest adjustment(String newCost, String reason, Long version) {
        CostAdjustmentRequest request = new CostAdjustmentRequest();
        request.setNewCost(newCost != null ? new BigDecimal(newCost) : null);
        request.setReason(reason);
        request.setExpectedVersion(version);
        return request;
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but was " + actual);
    }

    @Test
    void c4_costAdjustmentWritesRevaluationAndReasonedHistory() {
        productService.adjustCost(1L, adjustment("5.00", "Supplier price correction agreed", 3L), 9L);

        assertMoney("5.00", product.getCostPrice());

        ArgumentCaptor<StockMovement> movementCaptor = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository).save(movementCaptor.capture());
        StockMovement movement = movementCaptor.getValue();
        assertEquals(StockMovement.MovementType.REVALUATION, movement.getMovementType());
        assertMoney("-500.00", movement.getValuationAmount());

        ArgumentCaptor<ProductPriceHistory> historyCaptor = ArgumentCaptor.forClass(ProductPriceHistory.class);
        verify(priceHistoryRepository).save(historyCaptor.capture());
        ProductPriceHistory history = historyCaptor.getValue();
        assertEquals(ProductPriceHistory.PriceType.COST, history.getPriceType());
        assertEquals(ProductPriceHistory.Source.MANUAL_ADJUSTMENT, history.getSource());
        assertEquals("Supplier price correction agreed", history.getReason());
        assertMoney("10.00", history.getOldPrice());
        assertMoney("5.00", history.getNewPrice());
        assertEquals(100, history.getStockQtyAtChange());
        assertMoney("-500.00", history.getInventoryValueDelta());
        assertEquals(9L, history.getChangedBy().getId());
    }

    @Test
    void c4_costAdjustmentRejectsStaleVersion() {
        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> productService.adjustCost(1L, adjustment("5.00", "Supplier price correction agreed", 4L), 9L));

        assertMoney("10.00", product.getCostPrice());
        verify(stockMovementRepository, never()).save(any());
        verify(priceHistoryRepository, never()).save(any());
    }

    @Test
    void c4_costAdjustmentRequiresMeaningfulReason() {
        assertThrows(BusinessException.class,
                () -> productService.adjustCost(1L, adjustment("5.00", "short", 3L), 9L));
        assertMoney("10.00", product.getCostPrice());
    }

    @Test
    void c4_costAdjustmentRejectsNegativeCost() {
        assertThrows(BusinessException.class,
                () -> productService.adjustCost(1L, adjustment("-1.00", "Supplier price correction agreed", 3L), 9L));
        assertMoney("10.00", product.getCostPrice());
    }

    @Test
    void c4_updateProductCannotChangeCost() {
        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setSku("SKU-1");
        request.setName("Renamed");
        request.setUnitPrice(new BigDecimal("20.00"));
        request.setVersion(3L);

        productService.updateProduct(1L, request, 9L);

        assertEquals("Renamed", product.getName());
        assertMoney("10.00", product.getCostPrice());
        // No cost history row may be written by the update path.
        verify(priceHistoryRepository, never()).save(any());
    }

    @Test
    void c4_updateProductSellingPriceChangeRecordsSellingNotCostHistory() {
        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setSku("SKU-1");
        request.setName("Widget");
        request.setUnitPrice(new BigDecimal("25.00"));
        request.setVersion(3L);

        productService.updateProduct(1L, request, 9L);

        assertMoney("10.00", product.getCostPrice());
        ArgumentCaptor<ProductPriceHistory> captor = ArgumentCaptor.forClass(ProductPriceHistory.class);
        verify(priceHistoryRepository).save(captor.capture());
        assertEquals(ProductPriceHistory.PriceType.SELLING, captor.getValue().getPriceType());
    }

    @Test
    void c4_updateProductRejectsStaleVersion() {
        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setSku("SKU-1");
        request.setName("Renamed");
        request.setUnitPrice(new BigDecimal("25.00"));
        request.setVersion(4L);

        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> productService.updateProduct(1L, request, 9L));
        assertEquals("Widget", product.getName());
    }

    @Test
    void c4_updateProductRequiresVersion() {
        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setSku("SKU-1");
        request.setName("Renamed");
        request.setUnitPrice(new BigDecimal("25.00"));
        request.setVersion(null);

        assertThrows(BusinessException.class, () -> productService.updateProduct(1L, request, 9L));
    }

    @Test
    void c4_createRequiresCost() {
        ProductCreateRequest request = new ProductCreateRequest();
        request.setSku("SKU-NEW");
        request.setName("New");
        request.setUnitPrice(new BigDecimal("20.00"));
        request.setStockQuantity(5);
        request.setCostPrice(null);

        assertThrows(BusinessException.class, () -> productService.createProduct(request));
    }

    @Test
    void c7_adjustmentKeepsSixDecimalValuation() {
        product.setCostPrice(new BigDecimal("0.337835"));
        productService.adjustStock(9L, 1L, -1, "Damaged during handling", "DAMAGED_IN_STORE");

        ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository).save(captor.capture());
        assertMoney("-0.337835", captor.getValue().getValuationAmount());
    }

    @Test
    void c7_revaluationKeepsSixDecimalValuation() {
        productService.adjustCost(1L, adjustment("10.337835", "Supplier cost correction", 3L), 9L);

        ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository).save(captor.capture());
        assertMoney("33.783500", captor.getValue().getValuationAmount());
    }

    @Test
    void c9_openingStockWritesValuedMovementAndCostHistory() {
        User admin = new User();
        admin.setId(9L);
        lenient().when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        var principal = org.springframework.security.core.userdetails.User
                .withUsername("admin").password("unused").roles("ADMIN").build();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities()));
        lenient().when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
            Product saved = inv.getArgument(0);
            saved.setId(2L);
            return saved;
        });
        ProductCreateRequest request = new ProductCreateRequest();
        request.setSku("NEW");
        request.setName("Opening stock");
        request.setCostPrice(new BigDecimal("0.337835"));
        request.setStockQuantity(26);
        Product saved;
        try {
            saved = productService.createProduct(request);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }

        assertEquals(26, saved.getStockQuantity());
        ArgumentCaptor<StockMovement> movements = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository).save(movements.capture());
        StockMovement movement = movements.getValue();
        assertEquals(StockMovement.ReferenceType.OPENING_BALANCE, movement.getReferenceType());
        assertEquals(StockMovement.MovementType.IN, movement.getMovementType());
        assertEquals("SHOP", movement.getLocation());
        assertEquals(saved.getId(), movement.getReferenceId());
        assertEquals(26, movement.getQuantity());
        assertEquals(9L, movement.getCreatedBy().getId());
        assertMoney("8.783710", movement.getValuationAmount());
        assertMoney(saved.getCostPrice().multiply(BigDecimal.valueOf(26)).toPlainString(),
                movement.getValuationAmount());
        ArgumentCaptor<ProductPriceHistory> histories = ArgumentCaptor.forClass(ProductPriceHistory.class);
        verify(priceHistoryRepository).save(histories.capture());
        ProductPriceHistory history = histories.getValue();
        assertEquals(ProductPriceHistory.Source.OPENING_BALANCE, history.getSource());
        assertEquals(ProductPriceHistory.PriceType.COST, history.getPriceType());
        assertEquals(26, history.getStockQtyAtChange());
        assertMoney("0", history.getOldPrice());
        assertMoney("0.337835", history.getNewPrice());
        assertMoney("8.783710", history.getInventoryValueDelta());
        org.junit.jupiter.api.Assertions.assertNotNull(history.getReason());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 14, -1})
    void c10_deleteRejectsAnyNonzeroOwnedStock(int ownedUnits) {
        product.setStockQuantity(0);
        lenient().when(locationService.totalOwnedUnits(product)).thenReturn(ownedUnits);

        BusinessException exception = assertThrows(BusinessException.class, () -> productService.deleteProduct(1L));

        org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("write off"));
        assertEquals("SKU-1", product.getSku());
        org.junit.jupiter.api.Assertions.assertNull(product.getDeletedAt());
        verify(productRepository, never()).save(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void c10_deleteRejectsSaleOrPurchaseHistory(boolean saleHistory) {
        product.setStockQuantity(0);
        lenient().when(locationService.totalOwnedUnits(product)).thenReturn(0);
        lenient().when(saleItemRepository.existsByProductId(1L)).thenReturn(saleHistory);
        lenient().when(purchaseItemRepository.existsByProductId(1L)).thenReturn(!saleHistory);

        BusinessException exception = assertThrows(BusinessException.class, () -> productService.deleteProduct(1L));

        org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("deactivate"));
        verify(productRepository, never()).save(any());
    }

    @Test
    void c10_emptyProductWithoutHistoryCanBeDeletedUnderLock() {
        product.setStockQuantity(0);
        lenient().when(locationService.totalOwnedUnits(product)).thenReturn(0);

        productService.deleteProduct(1L);

        assertEquals(false, product.getIsActive());
        org.junit.jupiter.api.Assertions.assertNotNull(product.getDeletedAt());
        verify(productRepository).findByIdForUpdate(1L);
        verify(productRepository).save(product);
    }

    @Test
    void c4_productResponseCarriesVersionForRoundTrip() {
        assertEquals(3L, productService.getProductById(1L).getVersion());
    }

    @Test
    void c4_costAdjustmentEndpointIsAdminOnly() throws NoSuchMethodException {
        PreAuthorize annotation = com.bms.controller.ProductController.class
                .getMethod("adjustCost", Long.class, CostAdjustmentRequest.class,
                        org.springframework.security.core.Authentication.class)
                .getAnnotation(PreAuthorize.class);
        assertEquals("hasRole('ADMIN')", annotation.value());
    }
}