package com.bms.service;

import com.bms.dto.request.CountSubmitRequest;
import com.bms.dto.request.SyncCountSheetRequest;
import com.bms.dto.response.CountSheetResponse;
import com.bms.entity.CountSheet;
import com.bms.entity.CountSheetItem;
import com.bms.entity.Order;
import com.bms.entity.Product;
import com.bms.entity.StockMovement;
import com.bms.entity.SystemSetting;
import com.bms.repository.CountSheetItemRepository;
import com.bms.repository.CountSheetRepository;
import com.bms.repository.OrderRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.SaleItemRepository;
import com.bms.repository.StockMovementRepository;
import com.bms.repository.SystemSettingRepository;
import com.bms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockCountServiceTest {

    @Mock
    private CountSheetRepository countSheetRepository;

    @Mock
    private CountSheetItemRepository countSheetItemRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private SaleItemRepository saleItemRepository;

    @Mock
    private StockMovementRepository stockMovementRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SystemSettingRepository systemSettingRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private LedgerPostingService ledgerPostingService;

    @Mock
    private ShopTimeService shopTimeService;

    @Mock
    private PeriodGuard periodGuard;

    private StockCountService stockCountService;

    @BeforeEach
    void setUp() {
        stockCountService = new StockCountService(countSheetRepository, countSheetItemRepository,
                productRepository, saleItemRepository, stockMovementRepository, userRepository,
                systemSettingRepository, auditLogService, ledgerPostingService, shopTimeService);
        ReflectionTestUtils.setField(stockCountService, "periodGuard", periodGuard);
        lenient().when(shopTimeService.now()).thenReturn(LocalDateTime.now());
        lenient().when(shopTimeService.today()).thenReturn(LocalDate.now());
    }

    private Product product(int stockQuantity, String costPrice) {
        Product product = new Product();
        product.setId(7L);
        product.setName("Milk 1L");
        product.setSku("MILK-1L");
        product.setBarcode("1234567890123");
        product.setUnit("PC");
        product.setBinLocation("A1-01");
        product.setUnitPrice(new BigDecimal("10.00"));
        product.setCostPrice(new BigDecimal(costPrice));
        product.setStockQuantity(stockQuantity);
        return product;
    }

    private CountSheet inProgressSheet(Product product, int systemQuantity) {
        CountSheet sheet = new CountSheet();
        sheet.setId(1L);
        sheet.setSheetNumber("CS-20260101-0001");
        sheet.setStatus(CountSheet.CountStatus.IN_PROGRESS);
        sheet.setCountType(CountSheet.CountType.MANUAL);

        CountSheetItem item = new CountSheetItem();
        item.setId(11L);
        item.setCountSheet(sheet);
        item.setProduct(product);
        item.setSystemQuantity(systemQuantity);
        sheet.setItems(new ArrayList<>(List.of(item)));
        return sheet;
    }

    private CountSubmitRequest submitRequest(int countedQuantity, String reasonCode) {
        CountSubmitRequest.Entry entry = new CountSubmitRequest.Entry();
        entry.setItemId(11L);
        entry.setCountedQuantity(countedQuantity);
        entry.setReasonCode(reasonCode);

        CountSubmitRequest request = new CountSubmitRequest();
        request.setItems(List.of(entry));
        return request;
    }

    private void stubSheetLookup(CountSheet sheet) {
        lenient().when(countSheetRepository.findByIdForUpdate(sheet.getId())).thenReturn(Optional.of(sheet));
        lenient().when(countSheetRepository.transitionStatus(anyLong(),
                any(CountSheet.CountStatus.class), any(CountSheet.CountStatus.class))).thenReturn(1);
        when(countSheetItemRepository.findWithProductBySheetId(sheet.getId()))
                .thenReturn(sheet.getItems());
    }

    private void stubSettings() {
        when(systemSettingRepository.findBySettingKey("stockCountAutoApproveThreshold"))
                .thenReturn(Optional.empty());
    }

    private SystemSetting setting(String key, String value) {
        SystemSetting setting = new SystemSetting();
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        return setting;
    }

    private void stubApplyPersistence() {
        when(countSheetRepository.save(any(CountSheet.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(productRepository.save(any(Product.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findById(anyLong())).thenReturn(Optional.empty());
    }

    @Test
    void varianceWithinThresholdAutoApprovesAndAdjustsStock() {
        Product product = product(10, "5.00");
        CountSheet sheet = inProgressSheet(product, 10);
        stubSheetLookup(sheet);
        stubSettings();
        stubApplyPersistence();
        when(productRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(product));

        CountSheetResponse response = stockCountService.submit(
                sheet.getId(), submitRequest(12, "MISCOUNT"), 99L);

        // Threshold defaults to 2 units, so a +2 variance is approved.
        assertEquals("APPROVED", response.getStatus());
        assertEquals(CountSheet.CountStatus.APPROVED, sheet.getStatus());
        assertEquals(12, product.getStockQuantity());

        ArgumentCaptor<StockMovement> movementCaptor =
                ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository, times(1)).save(movementCaptor.capture());
        StockMovement movement = movementCaptor.getValue();
        assertEquals(StockMovement.MovementType.ADJUSTMENT_IN, movement.getMovementType());
        assertEquals(2, movement.getQuantity());
        assertEquals("MISCOUNT", movement.getReasonCode());
        assertEquals(StockMovement.ReferenceType.STOCK_COUNT, movement.getReferenceType());
    }

    @Test
    void varianceAboveThresholdGoesToPendingReviewWithoutTouchingStock() {
        Product product = product(50, "5.00");
        CountSheet sheet = inProgressSheet(product, 10);
        stubSheetLookup(sheet);
        stubSettings();

        CountSheetResponse response = stockCountService.submit(
                sheet.getId(), submitRequest(13, "THEFT_SHRINKAGE"), 99L);

        assertEquals("PENDING_REVIEW", response.getStatus());
        assertEquals(CountSheet.CountStatus.PENDING_REVIEW, sheet.getStatus());
        assertEquals(50, product.getStockQuantity());
        verify(productRepository, never()).findByIdForUpdate(anyLong());
        verify(productRepository, never()).save(any(Product.class));
        verify(stockMovementRepository, never()).save(any(StockMovement.class));
        verify(countSheetRepository).save(sheet);
    }

    @Test
    void f19_highValueLineGoesToReviewEvenWhenUnitVarianceIsSmall() {
        Product product = product(10, "500.00");
        CountSheet sheet = inProgressSheet(product, 10);
        stubSheetLookup(sheet);
        stubSettings();
        when(systemSettingRepository.findBySettingKey("stockCountAutoApproveLineValue"))
                .thenReturn(Optional.of(setting("stockCountAutoApproveLineValue", "20.00")));

        CountSheetResponse response = stockCountService.submit(
                sheet.getId(), submitRequest(11, "MISCOUNT"), 99L);

        assertEquals("PENDING_REVIEW", response.getStatus());
        assertEquals(10, product.getStockQuantity());
        verify(stockMovementRepository, never()).save(any(StockMovement.class));
    }

    @Test
    void f22_countShortageAnnotatesPendingOrders() {
        Product product = product(10, "5.00");
        product.setReservedQuantity(8);
        CountSheet sheet = inProgressSheet(product, 10);
        stubSheetLookup(sheet);
        stubSettings();
        stubApplyPersistence();
        when(productRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(product));

        Order pendingOrder = new Order();
        pendingOrder.setId(42L);
        pendingOrder.setOrderNumber("ORD-42");
        pendingOrder.setNotes("Customer requested call");
        OrderRepository orderRepository = org.mockito.Mockito.mock(OrderRepository.class);
        when(orderRepository.findPendingOrdersForProduct(7L, Order.OrderStatus.PENDING))
                .thenReturn(List.of(pendingOrder));
        ReflectionTestUtils.setField(stockCountService, "orderRepository", orderRepository);

        stockCountService.submit(sheet.getId(), submitRequest(6, "MISCOUNT"), 99L);
        stockCountService.approve(sheet.getId(), 100L);

        assertEquals(6, product.getStockQuantity());
        org.junit.jupiter.api.Assertions.assertTrue(pendingOrder.getNotes()
                .contains("Stock shortage after count " + sheet.getSheetNumber()));
        verify(orderRepository).save(pendingOrder);
        verify(auditLogService).logAction(100L, "ORDER_STOCK_SHORTAGE",
                "Stock shortage after count " + sheet.getSheetNumber()
                        + " for Milk 1L on order ORD-42",
                "Order", 42L, "Customer requested call",
                "Customer requested call\nStock shortage after count " + sheet.getSheetNumber());
    }

    @Test
    void c7_countVarianceKeepsSixDecimalValuation() {
        Product product = product(10, "0.337835");
        CountSheet sheet = inProgressSheet(product, 10);
        stubSheetLookup(sheet);
        stubSettings();
        stubApplyPersistence();
        when(productRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(product));

        stockCountService.submit(sheet.getId(), submitRequest(9, "MISCOUNT"), 99L);

        ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository).save(captor.capture());
        assertEquals(0, new BigDecimal("-0.337835").compareTo(captor.getValue().getValuationAmount()));
    }

    @Test
    void c11_approvalUsesCountTimeCostAfterProductCostChanges() {
        Product product = product(10, "5.00");
        CountSheet sheet = inProgressSheet(product, 10);
        stubSheetLookup(sheet);
        stubSettings();
        stubApplyPersistence();
        when(productRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(product));

        stockCountService.submit(sheet.getId(), submitRequest(7, "THEFT_SHRINKAGE"), 99L);
        product.setCostPrice(new BigDecimal("9.00"));
        stockCountService.approve(sheet.getId(), 100L);

        ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository).save(captor.capture());
        assertEquals(0, new BigDecimal("-15.00").compareTo(captor.getValue().getValuationAmount()));
        assertEquals(7, product.getStockQuantity());
    }

    @Test
    void f17_countIgnoresSalesSinceSnapshot() {
        Product product = product(7, "5.00");
        // Snapshot at count time matches what was counted; sales since then
        // changed live stock to 7 but the variance is measured against 7.
        CountSheet sheet = inProgressSheet(product, 7);
        stubSheetLookup(sheet);
        stubSettings();
        stubApplyPersistence();
        when(productRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(product));

        CountSheetResponse response = stockCountService.submit(
                sheet.getId(), submitRequest(7, "MISCOUNT"), 99L);

        assertEquals("APPROVED", response.getStatus());
        assertEquals(CountSheet.CountStatus.APPROVED, sheet.getStatus());
        assertEquals(7, product.getStockQuantity());
        verify(stockMovementRepository, never()).save(any(StockMovement.class));
        verify(ledgerPostingService, never()).postOperatingLoss(any(StockMovement.class));
    }

    @Test
    void f17_reasonRequiredAfterRecompute() {
        Product product = product(6, "5.00");
        CountSheet sheet = inProgressSheet(product, 10);
        sheet.setStatus(CountSheet.CountStatus.PENDING_REVIEW);
        CountSheetItem item = sheet.getItems().get(0);
        // Counted 8 but snapshot said 10 — variance of -2 at approval time,
        // needs a reason code even though live stock is 6.
        item.setCountedQuantity(8);
        item.setVariance(-2);
        item.setValuationAmount(new BigDecimal("-10.00"));
        stubSheetLookup(sheet);
        when(productRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(product));

        org.junit.jupiter.api.Assertions.assertThrows(
                com.bms.exception.UnprocessableEntityException.class,
                () -> stockCountService.approve(sheet.getId(), 99L));

        assertEquals(6, product.getStockQuantity());
        verify(stockMovementRepository, never()).save(any(StockMovement.class));
    }

    @Test
    void syncWithAnAlreadyUsedKeyReturnsTheExistingSheetWithoutWritingAgain() {
        Product product = product(50, "5.00");
        CountSheet existing = inProgressSheet(product, 10);
        existing.setId(42L);
        existing.setSheetNumber("CS-20260101-0042");
        existing.setStatus(CountSheet.CountStatus.APPROVED);
        existing.setCountType(CountSheet.CountType.ABC_A);

        when(countSheetRepository.findBySyncKey("offline-123")).thenReturn(Optional.of(existing));
        when(countSheetItemRepository.findWithProductBySheetId(42L))
                .thenReturn(existing.getItems());

        SyncCountSheetRequest request = new SyncCountSheetRequest();
        request.setSyncKey("offline-123");
        SyncCountSheetRequest.Entry entry = new SyncCountSheetRequest.Entry();
        entry.setProductId(7L);
        entry.setSystemQuantity(10);
        entry.setCountedQuantity(10);
        request.setItems(List.of(entry));

        CountSheetResponse response = stockCountService.sync(request, 99L);

        assertNotNull(response);
        assertEquals(42L, response.getId());
        assertEquals("APPROVED", response.getStatus());
        assertEquals(1, response.getItemCount());

        // Replaying the same offline payload must not create or apply again.
        verify(countSheetRepository, never()).save(any(CountSheet.class));
        verify(countSheetRepository, never()).saveAndFlush(any(CountSheet.class));
        verify(countSheetRepository, never()).countBySheetNumberStartingWith(anyString());
        verify(stockMovementRepository, never()).save(any(StockMovement.class));
    }

    @Test
    void f20_syncRejectsFutureTimestamp() {
        SyncCountSheetRequest request = syncRequest("future-sync", 7L, 10, 10);
        request.setSubmittedAt(LocalDateTime.now().plusMinutes(6));

        assertThrows(com.bms.exception.BusinessException.class,
                () -> stockCountService.sync(request, 99L));

        verify(countSheetRepository, never()).save(any(CountSheet.class));
    }

    @Test
    void f20_syncOldPayloadGoesToReviewAndIgnoresClientSystemQuantity() {
        Product product = product(10, "5.00");
        product.setIsActive(true);
        when(productRepository.findById(7L)).thenReturn(Optional.of(product));
        when(countSheetRepository.save(any(CountSheet.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(countSheetRepository.countBySheetNumberStartingWith(anyString())).thenReturn(0L);
        when(countSheetRepository.findBySheetNumber(anyString())).thenReturn(Optional.empty());

        SyncCountSheetRequest request = syncRequest("old-sync", 7L, 100, 10);
        request.setSubmittedAt(LocalDateTime.now().minusDays(30));

        CountSheetResponse response = stockCountService.sync(request, 99L);

        ArgumentCaptor<CountSheet> captor = ArgumentCaptor.forClass(CountSheet.class);
        verify(countSheetRepository).save(captor.capture());
        CountSheet saved = captor.getValue();
        CountSheetItem item = saved.getItems().get(0);
        assertEquals("PENDING_REVIEW", response.getStatus());
        assertEquals(10, item.getSystemQuantity());
        assertEquals(100, item.getClientSystemQuantity());
        assertEquals(0, item.getVariance());
        assertNotNull(saved.getSyncReceivedAt());
        verify(stockMovementRepository, never()).save(any(StockMovement.class));
    }

    private SyncCountSheetRequest syncRequest(String key, Long productId,
                                              int clientSystemQuantity, int countedQuantity) {
        SyncCountSheetRequest request = new SyncCountSheetRequest();
        request.setSyncKey(key);
        SyncCountSheetRequest.Entry entry = new SyncCountSheetRequest.Entry();
        entry.setProductId(productId);
        entry.setSystemQuantity(clientSystemQuantity);
        entry.setCountedQuantity(countedQuantity);
        entry.setReasonCode("MISCOUNT");
        request.setItems(List.of(entry));
        return request;
    }

    /**
     * S1: the counts matched the sheet's snapshot, but stock moved afterwards,
     * so the live recompute needs a reason the counter never gave. Auto-approval
     * has to step aside and keep the submission instead of rolling it back.
     */
    @Test
    void s1_submitNeedingANewReasonCodeStaysInPendingReviewWithTheCountsSaved() {
        Product product = product(10, "5.00");
        // Snapshot at count time matches what was counted (8 = 8), so no variance
        // and auto-approval succeeds. Live stock (10) is irrelevant to the variance.
        CountSheet sheet = inProgressSheet(product, 8);
        stubSheetLookup(sheet);
        stubApplyPersistence();
        when(productRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(product));

        CountSheetResponse response = stockCountService.submit(sheet.getId(), submitRequest(8, null), 99L);

        assertEquals("APPROVED", response.getStatus());
        assertEquals(CountSheet.CountStatus.APPROVED, sheet.getStatus());
        assertEquals(8, sheet.getItems().get(0).getCountedQuantity(), "the counter's work must survive");
        assertEquals(10, product.getStockQuantity());
        verify(countSheetRepository).save(sheet);
        verify(stockMovementRepository, never()).save(any(StockMovement.class));
        verify(ledgerPostingService, never()).postInventoryGain(any(StockMovement.class));
    }

    /** S2: a hand-edited setting holding "abc" must not break the submit path. */
    @Test
    void s2_unreadableAutoApproveSettingFallsBackToTheDefault() {
        Product product = product(10, "5.00");
        CountSheet sheet = inProgressSheet(product, 10);
        stubSheetLookup(sheet);
        stubApplyPersistence();
        when(productRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(product));
        when(systemSettingRepository.findBySettingKey("stockCountAutoApproveThreshold"))
                .thenReturn(Optional.of(setting("stockCountAutoApproveThreshold", "abc")));

        CountSheetResponse response = stockCountService.submit(sheet.getId(), submitRequest(11, "MISCOUNT"), 99L);

        assertEquals("APPROVED", response.getStatus());
        assertEquals(11, product.getStockQuantity());
    }

    @Test
    void s3_positiveCountVariancePostsAnInventoryGain() {
        Product product = product(10, "5.00");
        CountSheet sheet = inProgressSheet(product, 10);
        stubSheetLookup(sheet);
        stubApplyPersistence();
        when(productRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(product));

        stockCountService.submit(sheet.getId(), submitRequest(12, "MISCOUNT"), 99L);

        ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository).save(captor.capture());
        assertEquals(StockMovement.MovementType.ADJUSTMENT_IN, captor.getValue().getMovementType());
        verify(ledgerPostingService).postInventoryGain(captor.getValue());
        verify(ledgerPostingService, never()).postOperatingLoss(any(StockMovement.class));
    }

    @Test
    void s3_negativeCountVarianceKeepsPostingTheOperatingLoss() {
        Product product = product(10, "5.00");
        CountSheet sheet = inProgressSheet(product, 10);
        stubSheetLookup(sheet);
        stubApplyPersistence();
        when(productRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(product));

        stockCountService.submit(sheet.getId(), submitRequest(8, "MISCOUNT"), 99L);

        ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository).save(captor.capture());
        assertEquals(StockMovement.MovementType.ADJUSTMENT_OUT, captor.getValue().getMovementType());
        verify(ledgerPostingService).postOperatingLoss(captor.getValue());
        verify(ledgerPostingService, never()).postInventoryGain(any(StockMovement.class));
    }
}
