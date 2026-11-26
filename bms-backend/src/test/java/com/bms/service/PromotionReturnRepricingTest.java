package com.bms.service;

import com.bms.dto.request.SaleReturnRequest;
import com.bms.dto.response.SaleReturnResponse;
import com.bms.entity.Customer;
import com.bms.entity.InventoryLedger;
import com.bms.entity.PaymentMethod;
import com.bms.entity.Product;
import com.bms.entity.Role;
import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
import com.bms.entity.SaleReturn;
import com.bms.entity.User;
import com.bms.repository.CustomerRepository;
import com.bms.repository.ProductPriceHistoryRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.SaleItemRepository;
import com.bms.repository.SalePaymentRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.SaleReturnRepository;
import com.bms.repository.StockMovementRepository;
import com.bms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * Defect 7: a partial return must re-price the units the customer KEEPS at
 * their list price once the original promotion no longer qualifies. Refunding
 * the averaged promotional unit price lets the customer return part of a
 * BOGO / volume-tier bundle and still keep the deal on the remaining units.
 *
 * <p>Both cases use the write path with the sale and sale-item rows locked, so
 * the refund is the one actually persisted on the SaleReturnItem.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PromotionReturnRepricingTest {

    private static final long USER_ID = 5L;

    @Mock private SaleRepository saleRepository;
    @Mock private SaleItemRepository saleItemRepository;
    @Mock private SaleReturnRepository saleReturnRepository;
    @Mock private ProductRepository productRepository;
    @Mock private StockMovementRepository stockMovementRepository;
    @Mock private UserRepository userRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private PaymentValidationService paymentValidationService;
    @Mock private CashDrawerSessionService cashDrawerSessionService;
    @Mock private SalePaymentRepository salePaymentRepository;
    @Mock private LocationService locationService;
    @Mock private ProductPriceHistoryRepository priceHistoryRepository;
    @Mock private LedgerPostingService ledgerPostingService;
    @Mock private PeriodGuard periodGuard;
    @Mock private ShopTimeService shopTimeService;

    private SaleReturnService service;
    private User cashier;

    @BeforeEach
    void setUp() {
        service = new SaleReturnService();
        ReflectionTestUtils.setField(service, "saleRepository", saleRepository);
        ReflectionTestUtils.setField(service, "saleItemRepository", saleItemRepository);
        ReflectionTestUtils.setField(service, "saleReturnRepository", saleReturnRepository);
        ReflectionTestUtils.setField(service, "productRepository", productRepository);
        ReflectionTestUtils.setField(service, "stockMovementRepository", stockMovementRepository);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "customerRepository", customerRepository);
        ReflectionTestUtils.setField(service, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(service, "paymentValidationService", paymentValidationService);
        ReflectionTestUtils.setField(service, "cashDrawerSessionService", cashDrawerSessionService);
        ReflectionTestUtils.setField(service, "salePaymentRepository", salePaymentRepository);
        ReflectionTestUtils.setField(service, "locationService", locationService);
        ReflectionTestUtils.setField(service, "priceHistoryRepository", priceHistoryRepository);
        ReflectionTestUtils.setField(service, "ledgerPostingService", ledgerPostingService);
        ReflectionTestUtils.setField(service, "periodGuard", periodGuard);
        ReflectionTestUtils.setField(service, "shopTimeService", shopTimeService);

        LocalDateTime now = LocalDateTime.of(2026, 11, 10, 12, 0);
        lenient().when(shopTimeService.now()).thenReturn(now);

        Role role = new Role();
        role.setName(Role.RoleName.ROLE_CASHIER);
        cashier = new User();
        cashier.setId(USER_ID);
        cashier.setUsername("cashier");
        cashier.setRole(role);
        lenient().when(userRepository.findById(USER_ID)).thenReturn(Optional.of(cashier));

        lenient().when(paymentValidationService.parseMethod(anyString()))
                .thenAnswer(inv -> PaymentMethod.valueOf((String) inv.getArgument(0)));
        lenient().when(paymentValidationService.findOpenShift(USER_ID)).thenReturn(null);

        InventoryLedger posting = new InventoryLedger();
        posting.setUnitCostAfter(new BigDecimal("4.00"));
        lenient().when(ledgerPostingService.postInventory(any(Product.class), any(InventoryLedger.EventType.class),
                anyString(), any(), any(), anyInt(), any(), any(), any(), anyString(), any(), any()))
                .thenReturn(posting);
    }

    /**
     * Builds the persisted shape of a promotional sale: list price lives in
     * originalUnitPrice, the promotion-discounted price in unitPrice/totalPrice,
     * and the saved discount in lineDiscount/promotionDiscount.
     */
    private Sale promotionalSale(long saleId, String invoice, long itemId, long productId,
                                 String listPrice, String effectiveUnitPrice, int quantity,
                                 String amountPaid, String lineDiscount,
                                 long promotionId, String promotionName) {
        LocalDateTime now = LocalDateTime.of(2026, 11, 10, 12, 0);

        Product product = new Product();
        product.setId(productId);
        product.setName("Promo product " + productId);
        product.setSku("SKU-PROMO-" + productId);
        product.setUnitPrice(new BigDecimal(listPrice));
        product.setCostPrice(new BigDecimal("4.00"));
        product.setStockQuantity(quantity);

        Customer customer = new Customer();
        customer.setId(7L);
        customer.setCustomerCode("CUST-7");
        customer.setFirstName("Alice");
        customer.setCreditBalance(BigDecimal.ZERO);
        customer.setCurrentBalance(BigDecimal.ZERO);

        Sale sale = new Sale();
        sale.setId(saleId);
        sale.setInvoiceNumber(invoice);
        sale.setCashierId(USER_ID);
        sale.setSaleDate(now);
        sale.setSubtotal(new BigDecimal(amountPaid));
        sale.setTaxAmount(BigDecimal.ZERO);
        sale.setDiscountAmount(BigDecimal.ZERO);
        sale.setPromotionDiscount(new BigDecimal(lineDiscount));
        sale.setTotalAmount(new BigDecimal(amountPaid));
        sale.setAmountPaid(new BigDecimal(amountPaid));
        sale.setSaleType(Sale.SaleType.CASH);
        sale.setPaymentMethod(Sale.PaymentMethod.CASH);
        sale.setPaymentStatus(Sale.PaymentStatus.PAID);
        sale.setIsActive(true);
        sale.setIsVoided(false);
        sale.setCustomer(customer);

        SaleItem item = new SaleItem();
        item.setId(itemId);
        item.setSale(sale);
        item.setProduct(product);
        item.setQuantity(quantity);
        item.setQuantityRefunded(0);
        item.setUnitPrice(new BigDecimal(effectiveUnitPrice));
        item.setTotalPrice(new BigDecimal(amountPaid));
        item.setTaxAmount(BigDecimal.ZERO);
        item.setCostPriceAtSale(new BigDecimal("4.00"));
        item.setOriginalUnitPrice(new BigDecimal(listPrice));
        item.setLineDiscount(new BigDecimal(lineDiscount));
        item.setPromotionId(promotionId);
        item.setPromotionName(promotionName);
        sale.getItems().add(item);

        // Write path locks both rows before reading refundable quantities.
        lenient().when(saleRepository.findByIdForUpdate(saleId)).thenReturn(Optional.of(sale));
        lenient().when(saleRepository.save(any(Sale.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(saleItemRepository.findByIdForUpdate(itemId)).thenReturn(Optional.of(item));
        lenient().when(productRepository.findByIdForUpdate(productId)).thenReturn(Optional.of(product));
        lenient().when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(stockMovementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(customerRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(customer));
        lenient().when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(saleReturnRepository.save(any(SaleReturn.class))).thenAnswer(inv -> {
            SaleReturn saved = inv.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(saleId + 1);
            }
            return saved;
        });
        return sale;
    }

    private static SaleReturnRequest returnRequest(long saleItemId, int quantity) {
        SaleReturnRequest request = new SaleReturnRequest();
        request.setReason("Partial promotional return");
        // STORE_CREDIT avoids the cash-drawer gate while still charging a real refund.
        request.setRefundMethod("STORE_CREDIT");
        SaleReturnRequest.SaleReturnItemRequest line = new SaleReturnRequest.SaleReturnItemRequest();
        line.setSaleItemId(saleItemId);
        line.setQuantity(quantity);
        request.setItems(List.of(line));
        return request;
    }

    private static void assertMoney(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                message + " expected " + expected + " but was " + actual);
    }

    @Test
    void bogoPartialReturnRefundsNothingBecauseTheTwoKeptUnitsStillCostTwenty() {
        // Buy 2 get 1 at list 10.00: 3 units were sold for 20.00.
        // Returning one unit leaves 2 paid units worth 10.00 each = 20.00 kept,
        // so the refund must be 0.00, not 1/3 of the 20.00 paid.
        promotionalSale(100L, "INV-BOGO-1", 1000L, 10L,
                "10.00", "6.6667", 3, "20.00", "10.00", 44L, "Buy 2 Get 1 Free");

        SaleReturnResponse response = service.createSaleReturn(100L, returnRequest(1000L, 1), USER_ID);

        assertMoney("0.00", response.getRefundAmount(),
                "BOGO partial return must not refund an averaged unit price");
        assertMoney("0.00", response.getItems().get(0).getReturnAmount(),
                "BOGO returned line must carry the same re-priced refund");
        verify(saleRepository).findByIdForUpdate(100L);
        verify(saleItemRepository).findByIdForUpdate(1000L);
    }

    @Test
    void tieredPartialReturnRefundsOnlyTheValueLostFromTheDiscountedBundle() {
        // 20% off at qty >= 10, list 20.00: 10 units sold for 160.00.
        // Returning 9 leaves one unit, which no longer qualifies for the tier
        // and is worth the full 20.00, so the refund is 160.00 - 20.00 = 140.00.
        promotionalSale(200L, "INV-TIER-1", 2000L, 20L,
                "20.00", "16.0000", 10, "160.00", "40.00", 55L, "20% off 10+");

        SaleReturnResponse response = service.createSaleReturn(200L, returnRequest(2000L, 9), USER_ID);

        assertMoney("140.00", response.getRefundAmount(),
                "TIERED partial return must re-price the single kept unit at list");
        assertMoney("140.00", response.getItems().get(0).getReturnAmount(),
                "TIERED returned line must carry the same re-priced refund");
        verify(saleRepository).findByIdForUpdate(200L);
        verify(saleItemRepository).findByIdForUpdate(2000L);
    }
}
