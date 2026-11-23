package com.bms.service;

import com.bms.dto.request.SaleCreateRequest;
import com.bms.dto.request.SalePaymentRequest;
import com.bms.entity.CashShift;
import com.bms.entity.InventoryLedger;
import com.bms.entity.PaymentMethod;
import com.bms.entity.Product;
import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
import com.bms.entity.ShopInfo;
import com.bms.entity.SystemSetting;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.repository.CashShiftRepository;
import com.bms.repository.CustomerRepository;
import com.bms.repository.InventoryLedgerRepository;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUG-8: a missing cost must never become zero COGS. The sale either borrows
 * the latest valued ledger balance or is rejected before anything is written.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SaleServiceCostAtSaleTest {

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
    @Mock private InventoryLedgerRepository inventoryLedgerRepository;
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
        ReflectionTestUtils.setField(saleService, "inventoryLedgerRepository", inventoryLedgerRepository);
        ReflectionTestUtils.setField(saleService, "managerApprovalService", new ManagerApprovalService(
                userRepository, passwordEncoder, systemSettingRepository, auditLogService,
                new LoginAttemptTracker(), transactionManager));
        ShopTimeTestSupport.withClock(saleService);

        ShopInfo shopInfo = new ShopInfo();
        shopInfo.setTaxPercentage(BigDecimal.ZERO);
        shopInfo.setDiscountEnabled(false);
        shopInfo.setDiscountType(ShopInfo.DiscountType.AMOUNT);
        shopInfo.setDiscountValue(BigDecimal.ZERO);
        lenient().when(shopInfoRepository.findTopByOrderByIdAsc()).thenReturn(Optional.of(shopInfo));

        lenient().when(sequenceService.nextInvoiceNumber()).thenReturn("INV-COST-0001");
        CashShift shift = new CashShift();
        shift.setId(1L);
        lenient().when(paymentValidationService.requireOpenShift(anyLong())).thenReturn(shift);
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
            }
            return sale;
        });
        lenient().when(stockMovementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(salePaymentRepository.findBySaleIdOrderByPaidAtAsc(anyLong()))
                .thenReturn(new ArrayList<>());
        lenient().when(userRepository.findById(anyLong())).thenReturn(Optional.of(user(9L, "cashier")));
        lenient().when(systemSettingRepository.findBySettingKey(anyString())).thenReturn(Optional.empty());
    }

    private User user(long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword("stored-hash");
        return user;
    }

    private Product product(long id, String unitPrice) {
        Product product = new Product();
        product.setId(id);
        product.setName("Product " + id);
        product.setUnitPrice(new BigDecimal(unitPrice));
        product.setStockQuantity(100);
        products.put(id, product);
        return product;
    }

    private SaleCreateRequest request(long productId, int quantity) {
        SaleCreateRequest request = new SaleCreateRequest();
        List<SaleCreateRequest.SaleItemRequest> items = new ArrayList<>();
        SaleCreateRequest.SaleItemRequest item = new SaleCreateRequest.SaleItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        items.add(item);
        request.setItems(items);
        return request;
    }

    @Test
    void aProductWithoutCostOrLedgerHistoryRejectsTheSaleBeforeAnythingIsSaved() {
        Product product = product(1L, "100.00");
        product.setCostPrice(null);
        when(inventoryLedgerRepository.findTopByProductIdOrderByIdDesc(1L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> saleService.createSale(request(1L, 1), 9L));

        assertTrue(ex.getMessage().contains("no cost price"), ex.getMessage());
        verify(saleRepository, never()).save(any(Sale.class));
    }

    @Test
    void aMissingCostFallsBackToTheLatestValuedLedgerBalance() {
        Product product = product(1L, "100.00");
        product.setCostPrice(null);
        InventoryLedger ledger = new InventoryLedger();
        ledger.setUnitCostAfter(new BigDecimal("42.50"));
        when(inventoryLedgerRepository.findTopByProductIdOrderByIdDesc(1L)).thenReturn(Optional.of(ledger));

        saleService.createSale(request(1L, 1), 9L);

        ArgumentCaptor<Sale> captor = ArgumentCaptor.forClass(Sale.class);
        verify(saleRepository).save(captor.capture());
        BigDecimal costAtSale = captor.getValue().getItems().get(0).getCostPriceAtSale();
        assertEquals(0, new BigDecimal("42.50").compareTo(costAtSale),
                "the sale line must carry the ledger cost, never an implicit zero");
    }

    @Test
    void voidSaleOnOldLineWithNullCostFailsBeforeAnyStockIsRestored() {
        // Old sale line has null cost_price_at_sale (legacy data). The void must
        // reject with a clear business error and not restore stock or post ledgers.
        Product product = product(1L, "100.00");
        product.setCostPrice(new BigDecimal("60.00"));

        Sale sale = new Sale();
        sale.setId(100L);
        sale.setInvoiceNumber("INV-OLD");
        sale.setSaleDate(java.time.LocalDateTime.now().minusDays(1));
        sale.setIsVoided(false);
        sale.setVoidedAt(null);
        sale.setVoidedBy(null);
        sale.setVoidedReason(null);
        sale.setSaleType(Sale.SaleType.CASH);
        sale.setPaymentMethod(Sale.PaymentMethod.CASH);
        sale.setPaymentStatus(Sale.PaymentStatus.PAID);
        sale.setCashierId(9L);

        SaleItem item = new SaleItem();
        item.setId(1000L);
        item.setSale(sale);
        item.setProduct(product);
        item.setQuantity(1);
        item.setQuantityRefunded(0);
        item.setUnitPrice(new BigDecimal("100.00"));
        item.setTotalPrice(new BigDecimal("100.00"));
        // costPriceAtSale deliberately null — legacy row
        sale.getItems().add(item);

        User voidingUser = new User();
        voidingUser.setId(1L);
        voidingUser.setUsername("admin");
        voidingUser.setPassword("hash");
        com.bms.entity.Role role = new com.bms.entity.Role();
        role.setName(com.bms.entity.Role.RoleName.ROLE_ADMIN);
        voidingUser.setRole(role);

        lenient().when(saleRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(sale));
        lenient().when(userRepository.findById(1L)).thenReturn(Optional.of(voidingUser));
        lenient().when(salePaymentRepository.findBySaleIdOrderByPaidAtAsc(100L))
                .thenReturn(new ArrayList<>());
        lenient().when(saleReturnRepository.sumRefundedAmountBySaleId(100L)).thenReturn(null);
        lenient().when(saleItemRepository.findByIdForUpdate(1000L)).thenReturn(Optional.of(item));
        lenient().when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));
        lenient().when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(stockMovementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.doNothing().when(periodGuard).assertOpen(any(java.time.LocalDateTime.class));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> saleService.voidSale(100L, 1L, "Duplicate receipt"));

        assertTrue(ex.getMessage().contains("no cost price"), ex.getMessage());
        verify(saleItemRepository, never()).save(any());
    }
}
