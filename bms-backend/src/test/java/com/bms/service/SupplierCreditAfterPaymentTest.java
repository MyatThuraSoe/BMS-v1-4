package com.bms.service;

import com.bms.dto.request.ReturnToVendorRequest;
import com.bms.dto.response.PayablesAgingResponse;
import com.bms.dto.response.PurchaseResponse;
import com.bms.entity.InventoryLedger;
import com.bms.entity.Location;
import com.bms.entity.Product;
import com.bms.entity.Purchase;
import com.bms.entity.PurchaseItem;
import com.bms.entity.Supplier;
import com.bms.repository.PurchaseItemRepository;
import com.bms.repository.PurchasePaymentRepository;
import com.bms.repository.PurchaseRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.ProductPriceHistoryRepository;
import com.bms.repository.StockMovementRepository;
import com.bms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Defect 8: when a fully paid purchase is returned to the vendor in full, the
 * money is owed BACK by the supplier. The purchase records returnedAmount, but
 * payables aging drops the row because payable - paid is negative; the supplier
 * credit therefore disappears from every aging report.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupplierCreditAfterPaymentTest {

    private static final Long PO_ID = 1L;
    private static final Long USER_ID = 99L;
    private static final String SUPPLIER = "Credit Vendor";

    @Mock private PurchaseRepository purchaseRepository;
    @Mock private PurchaseItemRepository purchaseItemRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductPriceHistoryRepository priceHistoryRepository;
    @Mock private StockMovementRepository stockMovementRepository;
    @Mock private PurchasePaymentRepository purchasePaymentRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private LedgerPostingService ledgerPostingService;
    @Mock private LocationService locationService;
    @Mock private PeriodGuard periodGuard;
    @Mock private ShopTimeService shopTimeService;

    private PurchaseService purchaseService;
    private PurchasePaymentService purchasePaymentService;

    @BeforeEach
    void setUp() {
        purchasePaymentService = new PurchasePaymentService();
        ReflectionTestUtils.setField(purchasePaymentService, "purchaseRepository", purchaseRepository);
        ReflectionTestUtils.setField(purchasePaymentService, "purchasePaymentRepository", purchasePaymentRepository);
        ReflectionTestUtils.setField(purchasePaymentService, "shopTimeService", shopTimeService);

        purchaseService = new PurchaseService();
        ReflectionTestUtils.setField(purchaseService, "purchaseRepository", purchaseRepository);
        ReflectionTestUtils.setField(purchaseService, "purchaseItemRepository", purchaseItemRepository);
        ReflectionTestUtils.setField(purchaseService, "productRepository", productRepository);
        ReflectionTestUtils.setField(purchaseService, "priceHistoryRepository", priceHistoryRepository);
        ReflectionTestUtils.setField(purchaseService, "stockMovementRepository", stockMovementRepository);
        ReflectionTestUtils.setField(purchaseService, "userRepository", userRepository);
        ReflectionTestUtils.setField(purchaseService, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(purchaseService, "ledgerPostingService", ledgerPostingService);
        ReflectionTestUtils.setField(purchaseService, "locationService", locationService);
        ReflectionTestUtils.setField(purchaseService, "periodGuard", periodGuard);
        ReflectionTestUtils.setField(purchaseService, "shopTimeService", shopTimeService);
        ReflectionTestUtils.setField(purchaseService, "purchasePaymentService", purchasePaymentService);

        lenient().when(shopTimeService.now()).thenReturn(LocalDateTime.of(2026, 1, 15, 10, 0));
        lenient().when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        // The PO was paid in full before the return: 100.00 recorded against a 100.00 invoice.
        lenient().when(purchasePaymentRepository.sumAmountByPurchaseId(PO_ID))
                .thenReturn(new BigDecimal("100.00"));
        lenient().when(purchasePaymentRepository.sumAmountsByPurchaseIds(anyList()))
                .thenReturn(List.<Object[]>of(new Object[] { PO_ID, new BigDecimal("100.00") }));

        InventoryLedger posting = new InventoryLedger();
        posting.setUnitCostAfter(new BigDecimal("100.00"));
        lenient().when(ledgerPostingService.postInventory(
                        any(Product.class), any(), any(), any(), any(), anyInt(), any(), any(),
                        any(), any(), any(), anyLong(),
                        ArgumentMatchers.<Consumer<String>>any()))
                .thenReturn(posting);

        Location shop = new Location();
        shop.setId(1L);
        shop.setCode("SHOP");
        shop.setName("Shop");
        shop.setType(Location.LocationType.SHOP);
        lenient().when(locationService.getDefault(Location.LocationType.SHOP)).thenReturn(shop);
        lenient().when(locationService.findByCode("SHOP")).thenReturn(Optional.of(shop));
    }

    @Test
    void fullyPaidPurchaseReturnedToVendorMustKeepSupplierCreditInAging() {
        LocalDate asOf = LocalDate.of(2026, 1, 15);
        Purchase po = fullyPaidReceivedPurchase(asOf);
        stubPurchase(po);

        ReturnToVendorRequest request = new ReturnToVendorRequest();
        ReturnToVendorRequest.ReturnItem line = new ReturnToVendorRequest.ReturnItem();
        line.setPurchaseItemId(21L);
        line.setQuantity(1);
        request.setItems(List.of(line));
        request.setReason("Damaged on arrival");

        PurchaseResponse afterReturn = purchaseService.returnToVendor(PO_ID, request, USER_ID);

        assertEquals(new BigDecimal("100.00"), afterReturn.getReturnedAmount(),
                "returning the only unit at 100.00 records the full supplier credit");
        assertEquals(0, afterReturn.getPayableAmount().compareTo(BigDecimal.ZERO),
                "nothing is owed on the purchase after a full RTV");

        PayablesAgingResponse aging = purchasePaymentService.getPayablesAging(asOf);
        PayablesAgingResponse.SupplierAging supplierRow = aging.getSuppliers().stream()
                .filter(row -> SUPPLIER.equals(row.getSupplierName()))
                .findFirst()
                .orElse(null);

        assertNotNull(supplierRow,
                "a fully paid purchase returned in full must still appear in aging as a supplier credit");
        assertEquals(new BigDecimal("-100.00"), supplierRow.getTotalOutstanding(),
                "the supplier owes 100.00 back, so outstanding must be negative");
    }

    private Purchase fullyPaidReceivedPurchase(LocalDate purchaseDate) {
        Supplier supplier = new Supplier();
        supplier.setId(7L);
        supplier.setName(SUPPLIER);

        Product product = new Product();
        product.setId(11L);
        product.setName("Widget");
        product.setCostPrice(new BigDecimal("100.00"));
        product.setStockQuantity(1);

        Purchase po = new Purchase();
        po.setId(PO_ID);
        po.setPurchaseNumber("PO-CREDIT-1");
        po.setSupplier(supplier);
        po.setPurchaseDate(purchaseDate);
        po.setReceivingStatus(Purchase.ReceivingStatus.RECEIVED);
        po.setSubtotal(new BigDecimal("100.00"));
        po.setTotalAmount(new BigDecimal("100.00"));
        po.setReturnedAmount(BigDecimal.ZERO);
        po.setExchangeRate(BigDecimal.ONE);
        po.setCreatedBy(USER_ID);
        po.setItems(new ArrayList<>());

        PurchaseItem item = new PurchaseItem();
        item.setId(21L);
        item.setPurchase(po);
        item.setProduct(product);
        item.setQuantity(1);
        item.setUnitCost(new BigDecimal("100.00"));
        item.setTotalCost(new BigDecimal("100.00"));
        item.setLandedUnitCost(new BigDecimal("100.00"));
        item.setBaseUnitCost(new BigDecimal("100.00"));
        item.setReceivedQuantity(1);
        item.setReturnedQuantity(0);
        po.getItems().add(item);
        return po;
    }

    private void stubPurchase(Purchase po) {
        Product product = po.getItems().get(0).getProduct();
        lenient().when(purchaseRepository.findByIdForUpdate(PO_ID)).thenReturn(Optional.of(po));
        lenient().when(purchaseRepository.findActivePurchases(Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of(po)));
        lenient().when(productRepository.findByIdForUpdate(product.getId())).thenReturn(Optional.of(product));
        lenient().when(locationService.totalOwnedUnits(product)).thenReturn(1);
    }
}
