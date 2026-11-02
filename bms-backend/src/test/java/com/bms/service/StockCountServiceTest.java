package com.bms.service;

import com.bms.dto.request.CountSubmitRequest;
import com.bms.dto.request.SyncCountSheetRequest;
import com.bms.dto.response.CountSheetResponse;
import com.bms.entity.CountSheet;
import com.bms.entity.CountSheetItem;
import com.bms.entity.Product;
import com.bms.entity.StockMovement;
import com.bms.repository.CountSheetItemRepository;
import com.bms.repository.CountSheetRepository;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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

    private StockCountService stockCountService;

    @BeforeEach
    void setUp() {
        stockCountService = new StockCountService(countSheetRepository, countSheetItemRepository,
                productRepository, saleItemRepository, stockMovementRepository, userRepository,
                systemSettingRepository, auditLogService);
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
        when(countSheetRepository.findById(sheet.getId())).thenReturn(Optional.of(sheet));
        when(countSheetItemRepository.findWithProductBySheetId(sheet.getId()))
                .thenReturn(sheet.getItems());
    }

    private void stubSettings() {
        when(systemSettingRepository.findBySettingKey("stockCountAutoApproveThreshold"))
                .thenReturn(Optional.empty());
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
        Product product = product(50, "5.00");
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
        assertEquals(52, product.getStockQuantity());

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
}
