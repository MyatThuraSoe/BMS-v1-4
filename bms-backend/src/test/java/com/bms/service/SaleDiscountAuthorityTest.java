package com.bms.service;

import com.bms.dto.request.SaleCreateRequest;
import com.bms.dto.request.SalePaymentRequest;
import com.bms.entity.CashShift;
import com.bms.entity.PaymentMethod;
import com.bms.entity.Product;
import com.bms.entity.Role;
import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
import com.bms.entity.ShopInfo;
import com.bms.entity.SystemSetting;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.repository.CashShiftRepository;
import com.bms.repository.CustomerRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.SaleItemRepository;
import com.bms.repository.SalePaymentRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.SaleReturnRepository;
import com.bms.repository.ShopInfoRepository;
import com.bms.repository.StockMovementRepository;
import com.bms.repository.SystemSettingRepository;
import com.bms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O5: the discount a cashier may enter is capped by a setting, a manager (by
 * role or credentials) can go further, the below-cost policy is judged on the
 * discounted price, and every discount leaves an audit row.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SaleDiscountAuthorityTest {

    @Mock private SaleRepository saleRepository;
    @Mock private ProductRepository productRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private StockMovementRepository stockMovementRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private UserRepository userRepository;
    @Mock private SaleReturnService saleReturnService;
    @Mock private SaleItemRepository saleItemRepository;
    @Mock private CashShiftRepository cashShiftRepository;
    @Mock private ShopInfoRepository shopInfoRepository;
    @Mock private SequenceService sequenceService;
    @Mock private PromotionService promotionService;
    @Mock private PaymentValidationService paymentValidationService;
    @Mock private CashDrawerSessionService cashDrawerSessionService;
    @Mock private SalePaymentRepository salePaymentRepository;
    @Mock private SaleReturnRepository saleReturnRepository;
    @Mock private PeriodGuard periodGuard;
    @Mock private SystemSettingRepository systemSettingRepository;
    @Mock private LedgerPostingService ledgerPostingService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private PlatformTransactionManager transactionManager;

    private final Map<Long, Product> products = new HashMap<>();
    private SaleService saleService;

    @BeforeEach
    void setUp() {
        saleService = new SaleService();
        ReflectionTestUtils.setField(saleService, "saleRepository", saleRepository);
        ReflectionTestUtils.setField(saleService, "productRepository", productRepository);
        ReflectionTestUtils.setField(saleService, "customerRepository", customerRepository);
        ReflectionTestUtils.setField(saleService, "stockMovementRepository", stockMovementRepository);
        ReflectionTestUtils.setField(saleService, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(saleService, "userRepository", userRepository);
        ReflectionTestUtils.setField(saleService, "saleReturnService", saleReturnService);
        ReflectionTestUtils.setField(saleService, "saleItemRepository", saleItemRepository);
        ReflectionTestUtils.setField(saleService, "cashShiftRepository", cashShiftRepository);
        ReflectionTestUtils.setField(saleService, "shopInfoRepository", shopInfoRepository);
        ReflectionTestUtils.setField(saleService, "sequenceService", sequenceService);
        ReflectionTestUtils.setField(saleService, "promotionService", promotionService);
        ReflectionTestUtils.setField(saleService, "paymentValidationService", paymentValidationService);
        ReflectionTestUtils.setField(saleService, "cashDrawerSessionService", cashDrawerSessionService);
        ReflectionTestUtils.setField(saleService, "salePaymentRepository", salePaymentRepository);
        ReflectionTestUtils.setField(saleService, "saleReturnRepository", saleReturnRepository);
        ReflectionTestUtils.setField(saleService, "periodGuard", periodGuard);
        ReflectionTestUtils.setField(saleService, "systemSettingRepository", systemSettingRepository);
        ReflectionTestUtils.setField(saleService, "ledgerPostingService", ledgerPostingService);
        // M2: the manager password now goes through the shared approval service, so the
        // discount tests exercise the same path the till does rather than a stubbed clone.
        ReflectionTestUtils.setField(saleService, "managerApprovalService", new ManagerApprovalService(
                userRepository, passwordEncoder, systemSettingRepository, auditLogService,
                new LoginAttemptTracker(), transactionManager));
        ShopTimeTestSupport.withClock(saleService);

        ShopInfo shopInfo = new ShopInfo();
        shopInfo.setTaxPercentage(BigDecimal.ZERO);
        shopInfo.setDiscountEnabled(true);
        shopInfo.setDiscountType(ShopInfo.DiscountType.AMOUNT);
        shopInfo.setDiscountValue(BigDecimal.ZERO);
        lenient().when(shopInfoRepository.findTopByOrderByIdAsc()).thenReturn(Optional.of(shopInfo));

        lenient().when(sequenceService.nextInvoiceNumber()).thenReturn("INV-O5-0001");
        CashShift shift = new CashShift();
        shift.setId(1L);
        lenient().when(paymentValidationService.requireOpenShift(anyLong())).thenReturn(shift);
        lenient().when(paymentValidationService.findOpenShift(anyLong())).thenReturn(shift);
        lenient().when(paymentValidationService.parseMethod(anyString())).thenReturn(PaymentMethod.CASH);
        lenient().when(paymentValidationService.resolvePayments(any(), any(), any())).thenAnswer(inv -> {
            SalePaymentRequest line = new SalePaymentRequest();
            line.setMethod("CASH");
            line.setAmount(inv.getArgument(1));
            List<SalePaymentRequest> lines = new ArrayList<>();
            lines.add(line);
            return lines;
        });
        lenient().when(paymentValidationService.saveSalePayments(any(), any(), any()))
                .thenReturn(new ArrayList<>());
        lenient().when(promotionService.getActivePromotions(any())).thenReturn(new ArrayList<>());
        lenient().when(promotionService.calculateBestPrice(any(Product.class), anyInt(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Product p = inv.getArgument(0);
                    return new PricingQuote(p.getUnitPrice(), p.getUnitPrice(), BigDecimal.ZERO,
                            null, null, null, null, "BASE");
                });
        lenient().when(productRepository.findByIdForUpdate(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(products.get(inv.getArgument(0))));
        lenient().when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(saleRepository.save(any(Sale.class))).thenAnswer(inv -> {
            Sale sale = inv.getArgument(0);
            if (sale.getId() == null) {
                sale.setId(500L);
                for (SaleItem item : sale.getItems()) {
                    if (item.getId() == null) {
                        item.setId(600L);
                    }
                }
            }
            return sale;
        });
        lenient().when(stockMovementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(salePaymentRepository.findBySaleIdOrderByPaidAtAsc(anyLong()))
                .thenReturn(new ArrayList<>());

        // A plain cashier: no role, so the cashier cap applies.
        lenient().when(userRepository.findById(anyLong())).thenReturn(Optional.of(user(9L, "cashier", null)));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private User user(long id, String username, Role.RoleName role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword("stored-hash");
        if (role != null) {
            Role entity = new Role();
            entity.setName(role);
            user.setRole(entity);
        }
        return user;
    }

    private void signIn(User actor) {
        when(userRepository.findById(actor.getId())).thenReturn(Optional.of(actor));
    }

    private void setting(String key, String value) {
        SystemSetting setting = new SystemSetting();
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        lenient().when(systemSettingRepository.findBySettingKey(key)).thenReturn(Optional.of(setting));
    }

    private Product product(long id, String unitPrice, String costPrice) {
        Product product = new Product();
        product.setId(id);
        product.setName("Product " + id);
        product.setUnitPrice(new BigDecimal(unitPrice));
        product.setCostPrice(new BigDecimal(costPrice));
        product.setStockQuantity(100);
        products.put(id, product);
        return product;
    }

    private SaleCreateRequest request(String discount, long productId, int quantity) {
        SaleCreateRequest request = new SaleCreateRequest();
        List<SaleCreateRequest.SaleItemRequest> items = new ArrayList<>();
        SaleCreateRequest.SaleItemRequest item = new SaleCreateRequest.SaleItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        items.add(item);
        request.setItems(items);
        if (discount != null) {
            request.setDiscountAmount(new BigDecimal(discount));
        }
        return request;
    }

    // ------------------------------------------------------------------
    // Discount authority
    // ------------------------------------------------------------------

    @Test
    void cashierDiscountAboveTheLimitIsRejected() {
        product(1L, "100.00", "60.00");
        setting("shop.discount.maxPercentCashier", "10");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> saleService.createSale(request("50.00", 1L, 1), 9L));

        assertTrue(ex.getMessage().contains("manager approval"), ex.getMessage());
        verify(saleRepository, never()).save(any(Sale.class));
    }

    @Test
    void discountWithinTheCashierLimitIsAccepted() {
        product(1L, "100.00", "60.00");
        setting("shop.discount.maxPercentCashier", "10");

        saleService.createSale(request("10.00", 1L, 1), 9L);

        ArgumentCaptor<Sale> captor = ArgumentCaptor.forClass(Sale.class);
        verify(saleRepository).save(captor.capture());
        assertEquals(0, new BigDecimal("10.00").compareTo(captor.getValue().getDiscountAmount()));
    }

    @Test
    void managerByRoleMayDiscountUpToTheManagerLimit() {
        product(1L, "100.00", "60.00");
        setting("shop.discount.maxPercentCashier", "10");
        setting("shop.discount.maxPercentManager", "50");
        signIn(user(3L, "manager", Role.RoleName.ROLE_MANAGER));

        saleService.createSale(request("40.00", 1L, 1), 3L);

        ArgumentCaptor<Sale> captor = ArgumentCaptor.forClass(Sale.class);
        verify(saleRepository).save(captor.capture());
        assertEquals(0, new BigDecimal("40.00").compareTo(captor.getValue().getDiscountAmount()));
    }

    @Test
    void managerCredentialsClearTheCashierLimit() {
        product(1L, "100.00", "60.00");
        setting("shop.discount.maxPercentCashier", "10");
        User manager = user(3L, "manager", Role.RoleName.ROLE_MANAGER);
        when(userRepository.findActiveByUsernameIgnoreCase("manager")).thenReturn(Optional.of(manager));
        when(passwordEncoder.matches("hunter2", "stored-hash")).thenReturn(true);

        SaleCreateRequest request = request("50.00", 1L, 1);
        request.setManagerUsername("manager");
        request.setManagerPassword("hunter2");
        saleService.createSale(request, 9L);

        ArgumentCaptor<Sale> captor = ArgumentCaptor.forClass(Sale.class);
        verify(saleRepository).save(captor.capture());
        assertEquals(0, new BigDecimal("50.00").compareTo(captor.getValue().getDiscountAmount()));
    }

    @Test
    void managerCredentialsAreStillCappedByTheManagerLimit() {
        product(1L, "100.00", "60.00");
        setting("shop.discount.maxPercentCashier", "10");
        setting("shop.discount.maxPercentManager", "50");
        User manager = user(3L, "manager", Role.RoleName.ROLE_MANAGER);
        when(userRepository.findActiveByUsernameIgnoreCase("manager")).thenReturn(Optional.of(manager));
        when(passwordEncoder.matches("hunter2", "stored-hash")).thenReturn(true);

        SaleCreateRequest request = request("60.00", 1L, 1);
        request.setManagerUsername("manager");
        request.setManagerPassword("hunter2");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> saleService.createSale(request, 9L));

        assertTrue(ex.getMessage().contains("50"), ex.getMessage());
        verify(saleRepository, never()).save(any(Sale.class));
    }

    @Test
    void managerCredentialsAllowADiscountInsideTheManagerLimit() {
        product(1L, "100.00", "60.00");
        setting("shop.discount.maxPercentCashier", "10");
        setting("shop.discount.maxPercentManager", "50");
        User manager = user(3L, "manager", Role.RoleName.ROLE_MANAGER);
        when(userRepository.findActiveByUsernameIgnoreCase("manager")).thenReturn(Optional.of(manager));
        when(passwordEncoder.matches("hunter2", "stored-hash")).thenReturn(true);

        SaleCreateRequest request = request("50.00", 1L, 1);
        request.setManagerUsername("manager");
        request.setManagerPassword("hunter2");
        saleService.createSale(request, 9L);

        ArgumentCaptor<Sale> captor = ArgumentCaptor.forClass(Sale.class);
        verify(saleRepository).save(captor.capture());
        assertEquals(0, new BigDecimal("50.00").compareTo(captor.getValue().getDiscountAmount()));
    }

    @Test
    void wrongManagerCredentialsAreRejected() {
        product(1L, "100.00", "60.00");
        setting("shop.discount.maxPercentCashier", "10");
        when(userRepository.findActiveByUsernameIgnoreCase("manager"))
                .thenReturn(Optional.of(user(3L, "manager", Role.RoleName.ROLE_MANAGER)));
        when(passwordEncoder.matches("wrong", "stored-hash")).thenReturn(false);

        SaleCreateRequest request = request("50.00", 1L, 1);
        request.setManagerUsername("manager");
        request.setManagerPassword("wrong");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> saleService.createSale(request, 9L));

        assertTrue(ex.getMessage().contains("Invalid manager credentials"), ex.getMessage());
        verify(saleRepository, never()).save(any(Sale.class));
    }

    @Test
    void unreadableDiscountSettingFallsBackToTheCashierDefault() {
        product(1L, "100.00", "60.00");
        setting("shop.discount.maxPercentCashier", "abc");

        saleService.createSale(request("10.00", 1L, 1), 9L);

        verify(saleRepository).save(any(Sale.class));
        assertThrows(BusinessException.class,
                () -> saleService.createSale(request("11.00", 1L, 1), 9L));
    }

    @Test
    void everyDiscountIsAudited() {
        product(1L, "100.00", "60.00");
        setting("shop.discount.maxPercentCashier", "10");

        saleService.createSale(request("10.00", 1L, 1), 9L);
        saleService.createSale(request(null, 1L, 1), 9L);

        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(auditLogService, times(1)).logAction(eq(9L), eq("SALE_DISCOUNT"),
                description.capture(), eq("Sale"), anyLong(), any(), any());
        assertTrue(description.getValue().contains("10.00"), description.getValue());
    }

    // ------------------------------------------------------------------
    // M3: the cap judges the discount that is applied, in the mode the shop chose
    // ------------------------------------------------------------------

    /**
     * In PERCENTAGE mode the request's amount is never applied, so it must not be what the
     * cashier cap is compared against: 500 sent on a 100 sale with a 10% shop discount is a
     * 10 discount, which no cap should object to.
     */
    @Test
    void aPercentageShopDiscountIsNotJudgedByTheAmountInTheRequest() {
        product(1L, "100.00", "60.00");
        setting("shop.discount.maxPercentCashier", "10");
        shopDiscount(ShopInfo.DiscountType.PERCENTAGE, "10.00");

        saleService.createSale(request("500.00", 1L, 1), 9L);

        ArgumentCaptor<Sale> captor = ArgumentCaptor.forClass(Sale.class);
        verify(saleRepository).save(captor.capture());
        assertEquals(0, new BigDecimal("10.00").compareTo(captor.getValue().getDiscountAmount()),
                "the shop's 10% is what the customer gets");
    }

    @Test
    void aFixedShopDiscountIsNotJudgedByTheAmountInTheRequest() {
        product(1L, "100.00", "60.00");
        setting("shop.discount.maxPercentCashier", "10");
        shopDiscount(ShopInfo.DiscountType.FIXED, "20.00");

        saleService.createSale(request("500.00", 1L, 1), 9L);

        ArgumentCaptor<Sale> captor = ArgumentCaptor.forClass(Sale.class);
        verify(saleRepository).save(captor.capture());
        assertEquals(0, new BigDecimal("20.00").compareTo(captor.getValue().getDiscountAmount()));
    }

    /** The cap still bites in AMOUNT mode, where the entered figure is the real discount. */
    @Test
    void anAmountShopDiscountIsStillJudgedByTheAmount() {
        product(1L, "100.00", "60.00");
        setting("shop.discount.maxPercentCashier", "10");
        shopDiscount(ShopInfo.DiscountType.AMOUNT, "0.00");

        assertThrows(BusinessException.class, () -> saleService.createSale(request("50.00", 1L, 1), 9L));
        verify(saleRepository, never()).save(any(Sale.class));
    }

    /**
     * The M3 default is a decision, not a law: a shop that wants its own percentage policed by
     * the cashier cap can switch it on, and then the same 20% sale needs a manager.
     */
    @Test
    void aShopSetDiscountStaysUnjudgedUntilTheShopAsksForTheCap() {
        product(1L, "100.00", "60.00");
        setting("shop.discount.maxPercentCashier", "10");
        shopDiscount(ShopInfo.DiscountType.PERCENTAGE, "20.00");

        saleService.createSale(request("0.00", 1L, 1), 9L);
        verify(saleRepository, times(1)).save(any(Sale.class));

        setting("shop.discount.limitShopDiscounts", "true");
        assertThrows(BusinessException.class, () -> saleService.createSale(request("0.00", 1L, 1), 9L));
        verify(saleRepository, times(1)).save(any(Sale.class));
    }

    private void shopDiscount(ShopInfo.DiscountType type, String value) {
        ShopInfo info = new ShopInfo();
        info.setTaxPercentage(BigDecimal.ZERO);
        info.setDiscountEnabled(true);
        info.setDiscountType(type);
        info.setDiscountValue(new BigDecimal(value));
        lenient().when(shopInfoRepository.findTopByOrderByIdAsc()).thenReturn(Optional.of(info));
    }

    // ------------------------------------------------------------------
    // Below-cost after the discount allocation
    // ------------------------------------------------------------------

    @Test
    void blockedBelowCostUsesTheDiscountedNetPrice() {
        // Price 12 against cost 10 is fine until a 4 discount makes the line net 8.
        product(1L, "12.00", "10.00");
        setting("shop.discount.maxPercentCashier", "100");
        setting("shop.belowCostPolicy", "BLOCK");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> saleService.createSale(request("4.00", 1L, 1), 9L));

        assertTrue(ex.getMessage().contains("below cost"), ex.getMessage());
        verify(saleRepository, never()).save(any(Sale.class));
    }

    @Test
    void sameDiscountIsAllowedWhenThePolicyOnlyWarns() {
        product(1L, "12.00", "10.00");
        setting("shop.discount.maxPercentCashier", "100");
        setting("shop.belowCostPolicy", "ALLOW_WARN");

        saleService.createSale(request("4.00", 1L, 1), 9L);

        verify(saleRepository).save(any(Sale.class));
    }

    @Test
    void requireManagerBlocksACashierButNotAManager() {
        product(1L, "12.00", "10.00");
        setting("shop.discount.maxPercentCashier", "100");
        setting("shop.belowCostPolicy", "REQUIRE_MANAGER");

        assertThrows(BusinessException.class,
                () -> saleService.createSale(request("4.00", 1L, 1), 9L));

        signIn(user(3L, "manager", Role.RoleName.ROLE_MANAGER));
        saleService.createSale(request("4.00", 1L, 1), 3L);
        verify(saleRepository).save(any(Sale.class));
    }

    @Test
    void oneManagerOverrideClearsBothGates() {
        product(1L, "12.00", "10.00");
        setting("shop.discount.maxPercentCashier", "10");
        setting("shop.belowCostPolicy", "REQUIRE_MANAGER");
        when(userRepository.findActiveByUsernameIgnoreCase("manager"))
                .thenReturn(Optional.of(user(3L, "manager", Role.RoleName.ROLE_MANAGER)));
        when(passwordEncoder.matches("hunter2", "stored-hash")).thenReturn(true);

        // 4.00 on a 12.00 sale is over the 10% cashier cap and takes the line
        // under cost, so both gates need the same approved manager.
        SaleCreateRequest request = request("4.00", 1L, 1);
        request.setManagerUsername("manager");
        request.setManagerPassword("hunter2");
        saleService.createSale(request, 9L);

        verify(saleRepository).save(any(Sale.class));
    }
}
