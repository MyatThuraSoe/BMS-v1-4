package com.bms.service;

import com.bms.dto.request.ReceivePurchaseRequest;
import com.bms.dto.response.PurchaseResponse;
import com.bms.entity.Location;
import com.bms.entity.Product;
import com.bms.entity.Purchase;
import com.bms.entity.PurchaseItem;
import com.bms.entity.StockMovement;
import com.bms.repository.ProductPriceHistoryRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.PurchaseItemRepository;
import com.bms.repository.PurchaseRepository;
import com.bms.repository.StockMovementRepository;
import com.bms.repository.SupplierRepository;
import com.bms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PurchaseServiceReceivingTest {

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
    private LocationService locationService;
    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private PurchaseService purchaseService;

    private Product product;
    private PurchaseItem item;
    private Purchase purchase;
    private Location shop;
    private Location quarantine;

    @BeforeEach
    void setUp() {
        product = new Product();
        product.setId(7L);
        product.setName("Milk 1L");
        product.setSku("MILK-1L");
        product.setBarcode("1234567890123");
        product.setUnit("PC");
        product.setBinLocation("A1-01");
        product.setUnitPrice(new BigDecimal("10.00"));
        product.setCostPrice(new BigDecimal("4.00"));
        product.setStockQuantity(10);

        item = new PurchaseItem();
        item.setId(100L);
        item.setProduct(product);
        item.setQuantity(10);
        item.setUnitCost(new BigDecimal("5.00"));
        item.setTotalCost(new BigDecimal("50.00"));
        item.setBaseUnitCost(new BigDecimal("5.00"));
        item.setReceivedQuantity(0);
        item.setReturnedQuantity(0);

        purchase = new Purchase();
        purchase.setId(1L);
        purchase.setPurchaseNumber("PO-20260101-0001");
        purchase.setPurchaseDate(LocalDate.of(2026, 1, 1));
        purchase.setPaymentStatus(Purchase.PaymentStatus.PENDING);
        purchase.setReceivingStatus(Purchase.ReceivingStatus.ORDERED);
        purchase.setIsActive(true);
        purchase.setCurrencyCode("USD");
        purchase.setExchangeRate(BigDecimal.ONE);
        purchase.setSubtotal(new BigDecimal("50.00"));
        purchase.setTaxAmount(BigDecimal.ZERO);
        purchase.setDiscountAmount(BigDecimal.ZERO);
        purchase.setTotalAmount(new BigDecimal("50.00"));
        purchase.setCreatedBy(1L);
        purchase.setItems(new ArrayList<>(List.of(item)));

        shop = new Location();
        shop.setId(1L);
        shop.setCode("SHOP");
        shop.setName("Shop floor");
        shop.setType(Location.LocationType.SHOP);

        quarantine = new Location();
        quarantine.setId(2L);
        quarantine.setCode("QUARANTINE");
        quarantine.setName("Quarantine");
        quarantine.setType(Location.LocationType.QUARANTINE);
    }

    private void stubReceiving() {
        when(purchaseRepository.findById(1L)).thenReturn(Optional.of(purchase));
        when(locationService.getDefault(Location.LocationType.SHOP)).thenReturn(shop);
        when(locationService.getDefault(Location.LocationType.QUARANTINE)).thenReturn(quarantine);
        // Owned position before receipt: 10 shop units, no other buckets.
        when(locationService.totalOwnedUnits(product)).thenReturn(10);
        when(productRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(product));
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        when(productRepository.save(any(Product.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(purchaseRepository.save(any(Purchase.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(purchaseItemRepository.save(any(PurchaseItem.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void partialReceivingSplitsSellableAndQuarantineAndUpdatesWac() {
        stubReceiving();

        ReceivePurchaseRequest.ReceiveItem entry = new ReceivePurchaseRequest.ReceiveItem();
        entry.setPurchaseItemId(100L);
        entry.setReceivedQuantity(6);
        entry.setQuarantineQuantity(2);

        ReceivePurchaseRequest request = new ReceivePurchaseRequest();
        request.setItems(List.of(entry));
        request.setCancelRemainder(false);

        PurchaseResponse response = purchaseService.receivePurchase(1L, request, 99L);

        // Only the 4 sellable units hit shop-floor stock; 2 go to quarantine.
        assertEquals(14, product.getStockQuantity());

        // (4.00 * 10 old + 5.00 * 6 received) / 16 units = 4.375 (C7 keeps scale 6).
        assertEquals(0, product.getCostPrice().compareTo(new BigDecimal("4.375")));

        assertEquals(6, item.getReceivedQuantity());
        assertEquals("PARTIAL", response.getReceivingStatus());
        assertEquals(1, response.getItems().size());
        assertEquals(6, response.getItems().get(0).getReceivedQuantity());

        verify(locationService).addStock(product, quarantine, 2);

        ArgumentCaptor<StockMovement> movementCaptor =
                ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository, times(2)).save(movementCaptor.capture());
        List<StockMovement> movements = movementCaptor.getAllValues();
        assertTrue(movements.stream().anyMatch(movement ->
                movement.getQuantity() == 4 && "SHOP".equals(movement.getLocation())));
        assertTrue(movements.stream().anyMatch(movement ->
                movement.getQuantity() == 2 && "QUARANTINE".equals(movement.getLocation())));
        assertTrue(movements.stream().allMatch(movement ->
                movement.getMovementType() == StockMovement.MovementType.IN
                        && movement.getReferenceType() == StockMovement.ReferenceType.RECEIVING));

        verify(purchaseRepository).save(purchase);
        assertEquals(Purchase.ReceivingStatus.PARTIAL, purchase.getReceivingStatus());
    }
}
