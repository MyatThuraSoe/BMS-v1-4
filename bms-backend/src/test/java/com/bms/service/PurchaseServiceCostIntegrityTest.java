package com.bms.service;

import com.bms.dto.request.PurchaseCreateRequest;
import com.bms.dto.request.ReceivePurchaseRequest;
import com.bms.dto.request.ReturnToVendorRequest;
import com.bms.dto.request.StockTransferRequest;
import com.bms.entity.Location;
import com.bms.entity.LocationStock;
import com.bms.entity.Expense;
import com.bms.entity.Product;
import com.bms.entity.ProductPriceHistory;
import com.bms.entity.Purchase;
import com.bms.entity.PurchaseItem;
import com.bms.entity.StockMovement;
import com.bms.entity.StockTransfer;
import com.bms.exception.BusinessException;
import com.bms.repository.LocationRepository;
import com.bms.repository.LocationStockRepository;
import com.bms.repository.ExpenseRepository;
import com.bms.repository.ProductPriceHistoryRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.PurchaseItemRepository;
import com.bms.repository.PurchaseRepository;
import com.bms.repository.StockMovementRepository;
import com.bms.repository.StockTransferRepository;
import com.bms.repository.SupplierRepository;
import com.bms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Reproduction tests for audit findings C1, C2 and C3 (see
 * BMS-v1-4 Business Logic Fix Specification). Wires a REAL LocationService
 * backed by in-memory location buckets and a movement ledger into
 * PurchaseService so bucket math is genuinely exercised.
 *
 * Every c*_ test below fails on the pre-fix code.
 */
@ExtendWith(MockitoExtension.class)
class PurchaseServiceCostIntegrityTest {

    @Mock
    private PurchaseRepository purchaseRepository;
    @Mock
    private PurchaseItemRepository purchaseItemRepository;
    @Mock
    private SupplierRepository supplierRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private ProductPriceHistoryRepository priceHistoryRepository;
    @Mock
    private StockMovementRepository stockMovementRepository;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SequenceService sequenceService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private LocationRepository locationRepository;
    @Mock
    private LocationStockRepository locationStockRepository;
    @Mock
    private StockTransferRepository stockTransferRepository;
    @Mock
    private ExpenseRepository expenseRepository;

    private PurchaseService purchaseService;
    private LocationService locationService;

    private final Map<String, LocationStock> buckets = new HashMap<>();
    private final List<StockMovement> movements = new ArrayList<>();
    private final List<ProductPriceHistory> histories = new ArrayList<>();
    private final List<Expense> expenses = new ArrayList<>();
    private final Map<Long, Purchase> purchases = new HashMap<>();
    private final Map<Long, StockTransfer> transfers = new HashMap<>();
    private final AtomicLong purchaseIds = new AtomicLong(1);
    private final AtomicLong itemIds = new AtomicLong(100);
    private final AtomicLong transferIds = new AtomicLong(1);

    private Product product;
    private Location shop;
    private Location backroom;
    private Location quarantine;
    private Location transit;

    @BeforeEach
    void setUp() {
        shop = location(1L, "SHOP", Location.LocationType.SHOP);
        backroom = location(2L, "BACKROOM", Location.LocationType.BACKROOM);
        quarantine = location(3L, "QUARANTINE", Location.LocationType.QUARANTINE);
        transit = location(4L, "TRANSIT", Location.LocationType.TRANSIT);

        LocationService locationService = new LocationService(locationRepository,
                locationStockRepository, stockTransferRepository, productRepository,
                stockMovementRepository, userRepository, auditLogService);
        this.locationService = locationService;

        purchaseService = new PurchaseService();
        ReflectionTestUtils.setField(purchaseService, "purchaseRepository", purchaseRepository);
        ReflectionTestUtils.setField(purchaseService, "purchaseItemRepository", purchaseItemRepository);
        ReflectionTestUtils.setField(purchaseService, "supplierRepository", supplierRepository);
        ReflectionTestUtils.setField(purchaseService, "productRepository", productRepository);
        ReflectionTestUtils.setField(purchaseService, "priceHistoryRepository", priceHistoryRepository);
        ReflectionTestUtils.setField(purchaseService, "stockMovementRepository", stockMovementRepository);
        ReflectionTestUtils.setField(purchaseService, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(purchaseService, "userRepository", userRepository);
        ReflectionTestUtils.setField(purchaseService, "sequenceService", sequenceService);
        ReflectionTestUtils.setField(purchaseService, "locationService", locationService);
        ReflectionTestUtils.setField(purchaseService, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(purchaseService, "expenseRepository", expenseRepository);

        lenient().when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> {
            Expense expense = inv.getArgument(0);
            expenses.add(expense);
            return expense;
        });

        stubLocations();
        stubBuckets();
        stubLedgers();
        stubProducts();
        stubPurchases();
        stubTransfers();
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private Location location(Long id, String code, Location.LocationType type) {
        Location location = new Location();
        location.setId(id);
        location.setCode(code);
        location.setName(code);
        location.setType(type);
        location.setIsActive(true);
        return location;
    }

    private void stubLocations() {
        lenient().when(locationRepository.findByCodeIgnoreCase("SHOP")).thenReturn(Optional.of(shop));
        lenient().when(locationRepository.findByCodeIgnoreCase("BACKROOM")).thenReturn(Optional.of(backroom));
        lenient().when(locationRepository.findByCodeIgnoreCase("QUARANTINE")).thenReturn(Optional.of(quarantine));
        lenient().when(locationRepository.findByCodeIgnoreCase("TRANSIT")).thenReturn(Optional.of(transit));
        lenient().when(locationRepository.findAllByOrderByIdAsc())
                .thenReturn(List.of(shop, backroom, quarantine, transit));
        lenient().when(locationRepository.findByTypeAndIsActiveTrue(Location.LocationType.SHOP))
                .thenReturn(List.of(shop));
        lenient().when(locationRepository.findByTypeAndIsActiveTrue(Location.LocationType.BACKROOM))
                .thenReturn(List.of(backroom));
        lenient().when(locationRepository.findByTypeAndIsActiveTrue(Location.LocationType.QUARANTINE))
                .thenReturn(List.of(quarantine));
        lenient().when(locationRepository.findByTypeAndIsActiveTrue(Location.LocationType.TRANSIT))
                .thenReturn(List.of(transit));
        lenient().when(locationRepository.findById(1L)).thenReturn(Optional.of(shop));
        lenient().when(locationRepository.findById(2L)).thenReturn(Optional.of(backroom));
        lenient().when(locationRepository.findById(3L)).thenReturn(Optional.of(quarantine));
        lenient().when(locationRepository.findById(4L)).thenReturn(Optional.of(transit));
    }

    private String bucketKey(Long locationId, Long productId) {
        return locationId + ":" + productId;
    }

    private void stubBuckets() {
        lenient().when(locationStockRepository.findForUpdate(anyLong(), anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(
                        buckets.get(bucketKey(inv.getArgument(0), inv.getArgument(1)))));
        lenient().when(locationStockRepository.save(any(LocationStock.class))).thenAnswer(inv -> {
            LocationStock stock = inv.getArgument(0);
            buckets.put(bucketKey(stock.getLocation().getId(), stock.getProduct().getId()), stock);
            return stock;
        });
        lenient().when(locationStockRepository.sumNonShopQuantityByProduct(anyLong())).thenAnswer(inv -> {
            Long productId = inv.getArgument(0);
            return buckets.values().stream()
                    .filter(s -> productId.equals(s.getProduct().getId()))
                    .filter(s -> s.getLocation().getType() != Location.LocationType.SHOP)
                    .mapToLong(LocationStock::getQuantity).sum();
        });
        lenient().when(locationStockRepository.findPositiveByLocation(anyLong())).thenAnswer(inv -> {
            Long locationId = inv.getArgument(0);
            return buckets.values().stream()
                    .filter(s -> locationId.equals(s.getLocation().getId()))
                    .filter(s -> s.getQuantity() > 0)
                    .toList();
        });
        lenient().when(locationStockRepository.findByLocationIdAndProductId(anyLong(), anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(
                        buckets.get(bucketKey(inv.getArgument(0), inv.getArgument(1)))));
    }

    private void stubLedgers() {
        lenient().when(stockMovementRepository.save(any(StockMovement.class))).thenAnswer(inv -> {
            StockMovement movement = inv.getArgument(0);
            movements.add(movement);
            return movement;
        });
        lenient().when(priceHistoryRepository.save(any(ProductPriceHistory.class))).thenAnswer(inv -> {
            ProductPriceHistory history = inv.getArgument(0);
            histories.add(history);
            return history;
        });
        lenient().when(stockMovementRepository.findByReferenceAndPurchaseItem(any(), anyLong(), anyLong()))
                .thenAnswer(inv -> {
                    StockMovement.ReferenceType type = inv.getArgument(0);
                    Long referenceId = inv.getArgument(1);
                    Long purchaseItemId = inv.getArgument(2);
                    return movements.stream()
                            .filter(m -> m.getReferenceType() == type
                                    && referenceId.equals(m.getReferenceId())
                                    && purchaseItemId.equals(m.getPurchaseItemId()))
                            .toList();
                });
        lenient().when(stockMovementRepository.findByPurchaseItemId(anyLong()))
                .thenAnswer(inv -> movements.stream()
                        .filter(m -> inv.getArgument(0).equals(m.getPurchaseItemId()))
                        .toList());
        lenient().when(stockMovementRepository.findByProductAndReferenceTypeAndLocation(anyLong(), any(), any()))
                .thenAnswer(inv -> {
                    Long productId = inv.getArgument(0);
                    StockMovement.ReferenceType type = inv.getArgument(1);
                    String location = inv.getArgument(2);
                    return movements.stream()
                            .filter(m -> productId.equals(m.getProduct().getId())
                                    && m.getReferenceType() == type
                                    && location.equalsIgnoreCase(m.getLocation()))
                            .toList();
                });
    }

    private void stubProducts() {
        lenient().when(productRepository.findById(7L)).thenAnswer(inv -> Optional.ofNullable(product));
        lenient().when(productRepository.findByIdForUpdate(7L)).thenAnswer(inv -> Optional.ofNullable(product));
        lenient().when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(userRepository.findById(anyLong())).thenReturn(Optional.empty());
    }

    private void stubPurchases() {
        lenient().when(purchaseRepository.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(purchases.get(inv.getArgument(0))));
        lenient().when(purchaseRepository.save(any(Purchase.class))).thenAnswer(inv -> {
            Purchase purchase = inv.getArgument(0);
            register(purchase);
            return purchase;
        });
        lenient().when(purchaseRepository.saveAndFlush(any(Purchase.class))).thenAnswer(inv -> {
            Purchase purchase = inv.getArgument(0);
            register(purchase);
            return purchase;
        });
        lenient().when(purchaseItemRepository.save(any(PurchaseItem.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(sequenceService.nextPurchaseNumber())
                .thenAnswer(inv -> "PO-TEST-" + purchaseIds.get());
    }

    private void register(Purchase purchase) {
        if (purchase.getId() == null) {
            purchase.setId(purchaseIds.getAndIncrement());
        }
        for (PurchaseItem item : purchase.getItems()) {
            if (item.getId() == null) {
                item.setId(itemIds.getAndIncrement());
            }
        }
        purchases.put(purchase.getId(), purchase);
    }

    private void stubTransfers() {
        lenient().when(stockTransferRepository.countByTransferNumberStartingWith(anyString())).thenReturn(0L);
        lenient().when(stockTransferRepository.save(any(StockTransfer.class))).thenAnswer(inv -> {
            StockTransfer transfer = inv.getArgument(0);
            if (transfer.getId() == null) {
                transfer.setId(transferIds.getAndIncrement());
            }
            transfers.put(transfer.getId(), transfer);
            return transfer;
        });
        lenient().when(stockTransferRepository.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(transfers.get(inv.getArgument(0))));
    }

    private LocationService locationServiceForTest() {
        return locationService;
    }

    private StockTransfer createQuarantineReleaseTransfer(int quantity) {
        StockTransferRequest request = new StockTransferRequest();
        request.setFromLocationId(quarantine.getId());
        request.setToLocationId(shop.getId());
        StockTransferRequest.Item item = new StockTransferRequest.Item();
        item.setProductId(7L);
        item.setQuantity(quantity);
        request.setItems(List.of(item));
        return locationService.createTransfer(request, 99L);
    }

    private Product product(int shopQuantity, String costPrice) {
        product = new Product();
        product.setId(7L);
        product.setName("Test product");
        product.setSku("TEST-1");
        product.setUnitPrice(new BigDecimal("20.00"));
        product.setCostPrice(new BigDecimal(costPrice));
        product.setStockQuantity(shopQuantity);
        product.setIsActive(true);
        return product;
    }

    private PurchaseCreateRequest createRequest(int quantity, String unitCost, boolean receiveNow) {
        PurchaseCreateRequest request = new PurchaseCreateRequest();
        request.setPurchaseDate(LocalDate.now().toString());
        request.setReceiveNow(receiveNow);
        PurchaseCreateRequest.PurchaseItemRequest item = new PurchaseCreateRequest.PurchaseItemRequest();
        item.setProductId(7L);
        item.setQuantity(quantity);
        item.setUnitCost(new BigDecimal(unitCost));
        request.setItems(List.of(item));
        return request;
    }

    private Purchase doCreate(int quantity, String unitCost, boolean receiveNow) {
        purchaseService.createPurchase(createRequest(quantity, unitCost, receiveNow), 99L);
        return purchases.get(purchaseIds.get() - 1);
    }

    /** Builder for POs carrying discount / tax / freight, for C5/C6 scenarios. */
    private PurchaseCreateRequest landedRequest(int quantity, String unitCost, boolean receiveNow,
                                                String discount, String tax, boolean taxRecoverable,
                                                String freight) {
        PurchaseCreateRequest request = createRequest(quantity, unitCost, receiveNow);
        request.setDiscountAmount(new BigDecimal(discount));
        request.setTaxAmount(new BigDecimal(tax));
        request.setTaxRecoverable(taxRecoverable);
        if (freight != null) {
            PurchaseCreateRequest.ExpenseRequest expense = new PurchaseCreateRequest.ExpenseRequest();
            expense.setDescription("Freight");
            expense.setType("FREIGHT");
            expense.setAmount(new BigDecimal(freight));
            expense.setAllocationMethod("VALUE");
            request.setExpenses(List.of(expense));
        }
        return request;
    }

    private Purchase doCreateLanded(int quantity, String unitCost, boolean receiveNow,
                                    String discount, String tax, boolean taxRecoverable, String freight) {
        purchaseService.createPurchase(
                landedRequest(quantity, unitCost, receiveNow, discount, tax, taxRecoverable, freight), 99L);
        return purchases.get(purchaseIds.get() - 1);
    }

    private void stubManagerOverride() {
        com.bms.entity.Role role = new com.bms.entity.Role();
        role.setName(com.bms.entity.Role.RoleName.ROLE_MANAGER);
        com.bms.entity.User manager = new com.bms.entity.User();
        manager.setUsername("manager");
        manager.setPassword("hashed");
        manager.setRole(role);
        lenient().when(userRepository.findActiveByUsernameIgnoreCase("manager"))
                .thenReturn(Optional.of(manager));
        lenient().when(passwordEncoder.matches("pass", "hashed")).thenReturn(true);
    }

    private void receiveWithManager(Purchase purchase, PurchaseItem item, int quantity, boolean cancelRemainder) {
        ReceivePurchaseRequest request = new ReceivePurchaseRequest();
        ReceivePurchaseRequest.ReceiveItem entry = new ReceivePurchaseRequest.ReceiveItem();
        entry.setPurchaseItemId(item.getId());
        entry.setReceivedQuantity(quantity);
        entry.setQuarantineQuantity(0);
        request.setItems(List.of(entry));
        request.setCancelRemainder(cancelRemainder);
        request.setManagerUsername("manager");
        request.setManagerPassword("pass");
        purchaseService.receivePurchase(purchase.getId(), request, 99L);
    }

    private BigDecimal receivingValuation() {
        return movements.stream()
                .filter(m -> m.getReferenceType() == StockMovement.ReferenceType.RECEIVING)
                .map(StockMovement::getValuationAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void receive(Purchase purchase, PurchaseItem item, int quantity, int quarantineQuantity,
                         Location destination) {
        ReceivePurchaseRequest request = new ReceivePurchaseRequest();
        ReceivePurchaseRequest.ReceiveItem entry = new ReceivePurchaseRequest.ReceiveItem();
        entry.setPurchaseItemId(item.getId());
        entry.setReceivedQuantity(quantity);
        entry.setQuarantineQuantity(quarantineQuantity);
        entry.setLocationId(destination != null ? destination.getId() : null);
        request.setItems(List.of(entry));
        purchaseService.receivePurchase(purchase.getId(), request, 99L);
    }

    private void returnToVendor(Purchase purchase, PurchaseItem item, int quantity) {
        ReturnToVendorRequest request = new ReturnToVendorRequest();
        ReturnToVendorRequest.ReturnItem entry = new ReturnToVendorRequest.ReturnItem();
        entry.setPurchaseItemId(item.getId());
        entry.setQuantity(quantity);
        request.setItems(List.of(entry));
        request.setReason("Damaged on arrival");
        purchaseService.returnToVendor(purchase.getId(), request, 99L);
    }

    private int bucketQuantity(Location location) {
        LocationStock stock = buckets.get(bucketKey(location.getId(), 7L));
        return stock != null ? stock.getQuantity() : 0;
    }

    @Test
    void c7_fractionalReceiptAndReversalRetainInventoryValue() {
        product(0, "0");
        PurchaseCreateRequest request = createRequest(26, "1.00", true);
        request.setExchangeRate(new BigDecimal("0.337835"));
        request.setCurrencyCode("USD");
        purchaseService.createPurchase(request, 99L);
        Purchase purchase = purchases.get(1L);

        assertCost("0.337835");
        assertEquals(0, receivingValuation().compareTo(new BigDecimal("8.783710")));
        assertTrue(receivingValuation().subtract(new BigDecimal("8.7837")).abs()
                .compareTo(new BigDecimal("0.00005")) <= 0);
        purchaseService.deletePurchase(purchase.getId(), 99L);

        assertEquals(0, product.getStockQuantity());
        assertCost("0.337835");
        assertEquals(0, movements.stream().map(StockMovement::getValuationAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(BigDecimal.ZERO));
    }

    @Test
    void c7_splitReceiptAllocatesRoundingRemainderWithoutChangingCapitalisedValue() {
        product(0, "0");
        Purchase purchase = doCreateLanded(3, "1.00", false, "0", "0", false, "1.00");

        receive(purchase, purchase.getItems().get(0), 3, 1, shop);

        assertCost("1.333333");
        assertEquals(0, receivingValuation().compareTo(new BigDecimal("4.00")));
        assertEquals(0, histories.get(0).getInventoryValueDelta().compareTo(new BigDecimal("4.00")));
        assertEquals(2, product.getStockQuantity());
        assertEquals(1, bucketQuantity(quarantine));
    }

    @Test
    void c7_fractionalRtvPreservesMovementPrecision() {
        product(20, "0.337835");
        Purchase purchase = receivedPurchase(10, "0.337835");

        returnToVendor(purchase, purchase.getItems().get(0), 1);

        assertEquals(0, new BigDecimal("-0.337835").compareTo(movements.get(0).getValuationAmount()));
        assertCost("0.337835");
    }

    @Test
    void c8_rtvPersistsReconciliationAlertForNegativeRemainder() {
        product(10, "5.00");
        Purchase purchase = receivedPurchase(5, "20.00");

        returnToVendor(purchase, purchase.getItems().get(0), 5);

        assertCost("5.00");
        assertEquals(5, product.getStockQuantity());
        org.mockito.Mockito.verify(auditLogService).logAction(
                org.mockito.ArgumentMatchers.eq(99L), org.mockito.ArgumentMatchers.eq("COST_RECONCILIATION_ALERT"),
                org.mockito.ArgumentMatchers.contains("negative"), org.mockito.ArgumentMatchers.eq("Purchase"),
                org.mockito.ArgumentMatchers.eq(purchase.getId()), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void c8_reversalPersistsReconciliationAlertForNegativeRemainder() {
        product(10, "5.00");
        Purchase purchase = receivedPurchase(5, "20.00");

        purchaseService.deletePurchase(purchase.getId(), 99L);

        assertCost("5.00");
        assertEquals(5, product.getStockQuantity());
        org.mockito.Mockito.verify(auditLogService).logAction(
                org.mockito.ArgumentMatchers.eq(99L), org.mockito.ArgumentMatchers.eq("COST_RECONCILIATION_ALERT"),
                org.mockito.ArgumentMatchers.contains("negative"), org.mockito.ArgumentMatchers.eq("Purchase"),
                org.mockito.ArgumentMatchers.eq(purchase.getId()), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void c11_transferSnapshotsCompletionCostNotDraftOrLaterCost() {
        product(0, "0");
        Purchase purchase = doCreate(10, "5.00", false);
        receive(purchase, purchase.getItems().get(0), 10, 10, shop);
        StockTransfer transfer = createQuarantineReleaseTransfer(2);
        product.setCostPrice(new BigDecimal("9.00"));
        movements.clear();

        locationService.completeTransfer(transfer.getId(), 99L);
        product.setCostPrice(new BigDecimal("12.00"));

        assertEquals(2, movements.size());
        assertTrue(movements.stream().allMatch(m -> new BigDecimal("18.00").compareTo(m.getValuationAmount()) == 0));
    }

    @Test
    void c7_transferRetainsSixDecimalValuation() {
        product(0, "0");
        Purchase purchase = doCreate(10, "5.00", false);
        receive(purchase, purchase.getItems().get(0), 10, 10, shop);
        product.setCostPrice(new BigDecimal("0.337835"));
        movements.clear();

        locationService.completeTransfer(createQuarantineReleaseTransfer(2).getId(), 99L);

        assertEquals(2, movements.size());
        assertTrue(movements.stream().allMatch(m -> new BigDecimal("0.675670").compareTo(m.getValuationAmount()) == 0));
    }

    private void assertCost(String expected) {
        assertEquals(0, product.getCostPrice().compareTo(new BigDecimal(expected)),
                "expected WAC " + expected + " but was " + product.getCostPrice());
    }

    // ------------------------------------------------------------------
    // C1: weighted average must use all owned units
    // ------------------------------------------------------------------

    @Test
    void c1_weightedAverageUsesAllOwnedUnits() {
        product(0, "0.00");

        Purchase po1 = doCreate(10, "100.00", false);
        receive(po1, po1.getItems().get(0), 10, 0, backroom);
        assertCost("100.00");
        assertEquals(10, bucketQuantity(backroom));
        assertEquals(0, product.getStockQuantity());

        Purchase po2 = doCreate(10, "200.00", false);
        receive(po2, po2.getItems().get(0), 10, 0, shop);

        // (100 x 10 warehouse + 200 x 10 shop) / 20 owned = 150, not 200.
        assertCost("150.00");
        assertEquals(10, product.getStockQuantity());
        assertEquals(10, bucketQuantity(backroom));
    }

    @Test
    void c1_weightedAverageIncludesQuarantinedUnits() {
        product(0, "0.00");

        Purchase po1 = doCreate(10, "100.00", false);
        receive(po1, po1.getItems().get(0), 10, 4, shop);
        assertCost("100.00");
        assertEquals(6, product.getStockQuantity());
        assertEquals(4, bucketQuantity(quarantine));

        Purchase po2 = doCreate(10, "200.00", false);
        receive(po2, po2.getItems().get(0), 10, 0, shop);

        // Quarantined units were paid for: (100 x 10 + 200 x 10) / 20 = 150,
        // not the shop-only 162.50.
        assertCost("150.00");
    }

    @Test
    void c1_singleLocationReceiptsAreUnchanged() {
        product(10, "100.00");

        Purchase po = doCreate(10, "200.00", false);
        receive(po, po.getItems().get(0), 10, 0, shop);

        assertCost("150.00");
        assertEquals(20, product.getStockQuantity());
    }

    // ------------------------------------------------------------------
    // C3: deleting an unreceived PO must not touch stock
    // ------------------------------------------------------------------

    @Test
    void c3_deleteOrderedPurchaseDoesNotTouchStock() {
        product(100, "5.00");

        Purchase po = doCreate(50, "9.00", false);
        assertEquals(Purchase.ReceivingStatus.ORDERED, po.getReceivingStatus());
        assertEquals(100, product.getStockQuantity());
        assertCost("5.00");
        movements.clear();
        histories.clear();

        purchaseService.deletePurchase(po.getId(), 99L);

        assertEquals(100, product.getStockQuantity());
        assertCost("5.00");
        assertTrue(movements.isEmpty(), "deleting an ORDERED PO must not write stock movements");
        assertTrue(histories.isEmpty(), "deleting an ORDERED PO must not write cost history");
    }

    @Test
    void c3_deleteReceivedWarehousePurchaseReversesWarehouseOnly() {
        product(10, "5.00");

        Purchase po = doCreate(50, "11.00", false);
        receive(po, po.getItems().get(0), 50, 0, backroom);
        assertEquals(10, product.getStockQuantity());
        assertEquals(50, bucketQuantity(backroom));
        assertCost("10.00");

        purchaseService.deletePurchase(po.getId(), 99L);

        assertEquals(0, bucketQuantity(backroom), "warehouse stock must be reversed");
        assertEquals(10, product.getStockQuantity(), "shop stock must be untouched");
        assertCost("5.00");
    }

    @Test
    void c3_deletePartiallyReceivedReversesExactlyReceived() {
        product(100, "4.00");

        Purchase po = doCreate(100, "12.00", false);
        receive(po, po.getItems().get(0), 60, 0, shop);
        assertEquals(160, product.getStockQuantity());
        assertCost("7.00");

        purchaseService.deletePurchase(po.getId(), 99L);

        assertEquals(100, product.getStockQuantity());
        assertCost("4.00");
        int reversed = movements.stream()
                .filter(m -> m.getMovementType() == StockMovement.MovementType.OUT)
                .mapToInt(StockMovement::getQuantity).sum();
        assertEquals(60, reversed, "exactly the received 60 units must be reversed");
    }

    // ------------------------------------------------------------------
    // C2: return-to-vendor must be a complete transaction
    // ------------------------------------------------------------------

    @Test
    void c2_returnToVendorRevaluesWac() {
        // 20 owned @ WAC 20.00 (value 400); the PO's own landed cost is 30.
        product(20, "20.00");
        Purchase po = receivedPurchase(20, "30.00");

        returnToVendor(po, po.getItems().get(0), 10);

        assertEquals(10, product.getStockQuantity());
        // (400 - 30 x 10) / 10 remaining = 10.00, not the old 20.00 (200 booked).
        assertCost("10.00");
        // D1: supplier credit of 300 reduces the payable by the same amount.
        assertEquals(0, po.getReturnedAmount().compareTo(new BigDecimal("300.00")));
        assertEquals(0, po.getPayableAmount().compareTo(
                po.getTotalAmount().subtract(new BigDecimal("300.00"))));
    }

    @Test
    void c2_returnToVendorAfterQuarantineReleaseDrawsFromShop() {
        product(0, "0.00");

        Purchase po1 = doCreate(10, "10.00", false);
        receive(po1, po1.getItems().get(0), 10, 10, shop);
        Purchase po2 = doCreate(10, "10.00", false);
        receive(po2, po2.getItems().get(0), 10, 10, shop);
        assertEquals(20, bucketQuantity(quarantine));

        // Release PO-1's quarantined goods to the shop floor. The transfer is
        // attributed FIFO, so PO-1's scoped quarantine drops to zero.
        locationServiceForTest().completeTransfer(
                createQuarantineReleaseTransfer(10).getId(), 99L);
        assertEquals(10, bucketQuantity(quarantine));
        assertEquals(10, product.getStockQuantity());

        returnToVendor(po1, po1.getItems().get(0), 10);

        // All 10 must come from shop; PO-2's quarantine stays intact.
        assertEquals(10, bucketQuantity(quarantine), "PO-2 quarantine must not be drained");
        assertEquals(0, product.getStockQuantity());
    }

    @Test
    void c2_returnToVendorDoesNotDrainOtherPoQuarantine() {
        product(0, "0.00");

        Purchase po1 = doCreate(10, "10.00", false);
        receive(po1, po1.getItems().get(0), 10, 4, shop);
        Purchase po2 = doCreate(10, "10.00", false);
        receive(po2, po2.getItems().get(0), 10, 10, shop);

        assertEquals(6, product.getStockQuantity());
        assertEquals(14, bucketQuantity(quarantine));

        returnToVendor(po1, po1.getItems().get(0), 10);

        // PO-1 owns only 4 quarantined units; the other 6 come from shop.
        // PO-2's 10 quarantined units must stay untouched.
        assertEquals(10, bucketQuantity(quarantine), "PO-2 quarantine must not be drained");
        assertEquals(0, product.getStockQuantity());
    }

    @Test
    void c2_returnToVendorRejectsMoreThanReceivedMinusReturned() {
        product(20, "20.00");
        Purchase po = receivedPurchase(20, "30.00");
        po.getItems().get(0).setReturnedQuantity(15);

        assertThrows(BusinessException.class,
                () -> returnToVendor(po, po.getItems().get(0), 10));
    }

    // ------------------------------------------------------------------
    // C5/C6: landed cost follows what was actually paid and received
    // ------------------------------------------------------------------

    @Test
    void c5_discountAndNonRecoverableTaxAreCapitalised() {
        product(0, "0.00");

        // Acceptance A: 100 @ 10, discount 100, non-recoverable tax 50.
        Purchase po = doCreateLanded(100, "10.00", true, "100.00", "50.00", false, null);

        // Capitalised 950 (was 1000): (1000 - 100 + 50) / 100 = 9.50 per unit.
        assertCost("9.50");
        assertEquals(0, receivingValuation().compareTo(new BigDecimal("950.00")));
        assertEquals(0, po.getItems().get(0).getBaseUnitCost().compareTo(new BigDecimal("9.5000")));
        // Payable 950; I2: capitalised == payable - recoverable tax (0).
        assertEquals(0, po.getPayableAmount().compareTo(new BigDecimal("950.00")));
        assertEquals(0, receivingValuation().compareTo(po.getPayableAmount()));
    }

    @Test
    void c5_recoverableTaxIsNotCapitalised() {
        product(0, "0.00");

        Purchase po = doCreateLanded(100, "10.00", true, "0.00", "50.00", true, null);

        // Recoverable tax stays out of inventory cost: capitalised 1000.
        assertCost("10.00");
        assertEquals(0, receivingValuation().compareTo(new BigDecimal("1000.00")));
        // Payable 1050; I2: capitalised 1000 == payable 1050 - recoverable tax 50.
        assertEquals(0, po.getPayableAmount().compareTo(new BigDecimal("1050.00")));
        assertEquals(0, receivingValuation().compareTo(
                po.getPayableAmount().subtract(new BigDecimal("50.00"))));
    }

    @Test
    void c6_overReceiptDoesNotOverCapitaliseFreight() {
        product(0, "0.00");
        stubManagerOverride();

        // Acceptance B: PO 100 @ 10 + freight 50, received 120.
        Purchase po = doCreateLanded(100, "10.00", false, "0.00", "0.00", false, "50.00");
        receiveWithManager(po, po.getItems().get(0), 120, false);

        // Goods 1200 + freight capped at exactly 50 (was 60) = 1250.
        assertEquals(0, receivingValuation().compareTo(new BigDecimal("1250.00")));
        BigDecimal freightCapitalised = receivingValuation().subtract(new BigDecimal("1200.00"));
        assertEquals(0, freightCapitalised.compareTo(new BigDecimal("50.00")));
        assertEquals(0, po.getTotalAmount().compareTo(new BigDecimal("1250.00")));
        assertEquals(0, po.getVarianceAmount().compareTo(BigDecimal.ZERO));
        assertEquals(Purchase.ReceivingStatus.RECEIVED, po.getReceivingStatus());
    }

    @Test
    void c6_cancelledRemainderExpensesUnabsorbedFreight() {
        product(0, "0.00");

        // Acceptance C: PO 100 @ 10 + freight 50, received 60, remainder cancelled.
        Purchase po = doCreateLanded(100, "10.00", false, "0.00", "0.00", false, "50.00");
        receiveWithManager(po, po.getItems().get(0), 60, true);

        // Goods 600 + freight 30 capitalised = 630; the other 20 is variance.
        assertEquals(0, receivingValuation().compareTo(new BigDecimal("630.00")));
        assertEquals(0, po.getVarianceAmount().compareTo(new BigDecimal("20.00")));
        assertEquals(0, po.getTotalAmount().compareTo(new BigDecimal("630.00")));
        assertEquals(Purchase.ReceivingStatus.RECEIVED, po.getReceivingStatus());
        // Freight fully accounted: 30 capitalised + 20 variance = 50.
        BigDecimal freightCapitalised = receivingValuation().subtract(new BigDecimal("600.00"));
        assertEquals(0, freightCapitalised.add(po.getVarianceAmount())
                .compareTo(new BigDecimal("50.00")));
        // D3: the unabsorbed 20 is posted to the P&L as purchase variance.
        assertEquals(1, expenses.size());
        assertEquals(Expense.ExpenseCategory.PURCHASE_VARIANCE, expenses.get(0).getCategory());
        assertEquals(0, expenses.get(0).getAmount().compareTo(new BigDecimal("20.00")));
        // I2: capitalised 630 == payable 630 - recoverable tax 0.
        assertEquals(0, receivingValuation().compareTo(po.getPayableAmount()));
    }

    @Test
    void c6_partialReceiptCapitalisesFreightProportionallyThenCompletes() {
        product(0, "0.00");

        Purchase po = doCreateLanded(100, "10.00", false, "0.00", "0.00", false, "50.00");
        receive(po, po.getItems().get(0), 60, 0, shop);

        // 60/100 of freight 50 = 30 capitalised so far; PO still open.
        assertEquals(0, receivingValuation().compareTo(new BigDecimal("630.00")));
        assertCost("10.50");
        assertEquals(Purchase.ReceivingStatus.PARTIAL, po.getReceivingStatus());
        assertEquals(0, po.getVarianceAmount().compareTo(BigDecimal.ZERO));

        receive(po, po.getItems().get(0), 40, 0, shop);

        // Remaining 20 of freight absorbed; total 1050, no variance.
        assertEquals(0, receivingValuation().compareTo(new BigDecimal("1050.00")));
        assertEquals(0, po.getTotalAmount().compareTo(new BigDecimal("1050.00")));
        assertEquals(0, po.getVarianceAmount().compareTo(BigDecimal.ZERO));
        assertEquals(Purchase.ReceivingStatus.RECEIVED, po.getReceivingStatus());
        assertTrue(expenses.isEmpty(), "fully absorbed freight must not create variance");
    }

    @Test
    void c5_rtvCreditDoesNotDoubleCountCapitalisedTax() {
        product(0, "0.00");

        // Non-recoverable tax 10 capitalised: landed unit cost (200 + 10)/20 = 10.50.
        Purchase po = doCreateLanded(20, "10.00", true, "0.00", "10.00", false, null);
        assertEquals(0, po.getItems().get(0).getBaseUnitCost().compareTo(new BigDecimal("10.5000")));

        returnToVendor(po, po.getItems().get(0), 10);

        // Credit is landed cost only (105), not 110 with tax added a second time.
        assertEquals(0, po.getReturnedAmount().compareTo(new BigDecimal("105.00")));
        assertEquals(0, po.getPayableAmount().compareTo(new BigDecimal("105.00")));
    }

    @Test
    void c5_rtvCreditAddsProRataOnlyForRecoverableTax() {
        product(0, "0.00");

        // Recoverable tax 10 stays out of cost: landed unit cost 10.00.
        Purchase po = doCreateLanded(20, "10.00", true, "0.00", "10.00", true, null);
        assertEquals(0, po.getItems().get(0).getBaseUnitCost().compareTo(new BigDecimal("10.0000")));

        returnToVendor(po, po.getItems().get(0), 10);

        // Credit 100 landed + pro-rata recoverable tax 5 = 105.
        assertEquals(0, po.getReturnedAmount().compareTo(new BigDecimal("105.00")));
    }

    /** A fully received PO built directly, for RTV-focused scenarios. */
    private Purchase receivedPurchase(int quantity, String baseUnitCost) {
        Purchase purchase = new Purchase();
        purchase.setId(purchaseIds.getAndIncrement());
        purchase.setPurchaseNumber("PO-TEST-" + purchase.getId());
        purchase.setPurchaseDate(LocalDate.now());
        purchase.setReceivingStatus(Purchase.ReceivingStatus.RECEIVED);
        purchase.setPaymentStatus(Purchase.PaymentStatus.PENDING);
        purchase.setIsActive(true);
        purchase.setCurrencyCode("USD");
        purchase.setExchangeRate(BigDecimal.ONE);
        purchase.setSubtotal(new BigDecimal(baseUnitCost).multiply(BigDecimal.valueOf(quantity)));
        purchase.setTaxAmount(BigDecimal.ZERO);
        purchase.setDiscountAmount(BigDecimal.ZERO);
        purchase.setTotalAmount(purchase.getSubtotal());
        purchase.setCreatedBy(99L);

        PurchaseItem item = new PurchaseItem();
        item.setId(itemIds.getAndIncrement());
        item.setPurchase(purchase);
        item.setProduct(product);
        item.setQuantity(quantity);
        item.setUnitCost(new BigDecimal(baseUnitCost));
        item.setTotalCost(purchase.getSubtotal());
        item.setBaseUnitCost(new BigDecimal(baseUnitCost));
        item.setLandedUnitCost(new BigDecimal(baseUnitCost));
        item.setReceivedQuantity(quantity);
        item.setReturnedQuantity(0);
        purchase.setItems(new ArrayList<>(List.of(item)));

        purchases.put(purchase.getId(), purchase);
        return purchase;
    }
}