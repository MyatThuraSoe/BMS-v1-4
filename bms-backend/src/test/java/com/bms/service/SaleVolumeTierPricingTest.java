package com.bms.service;

import com.bms.dto.request.SaleCreateRequest;
import com.bms.dto.request.SalePaymentRequest;
import com.bms.dto.response.SaleResponse;
import com.bms.entity.CashShift;
import com.bms.entity.PaymentMethod;
import com.bms.entity.Product;
import com.bms.entity.Sale;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TASK-3 / TASK-5: volume-tier pricing is evaluated on the merged quantity per
 * product, not per request line. Two lines of 5 must price the same as one line
 * of 10 when a tier exists at 10.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SaleVolumeTierPricingTest {

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

        lenient().when(sequenceService.nextInvoiceNumber()).thenReturn("INV-TIER-0001");
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
        lenient().when(productRepository.findByIdForUpdate(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(products.get(inv.getArgument(0))));
        lenient().when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(saleRepository.save(any(Sale.class))).thenAnswer(inv -> {
            Sale sale = inv.getArgument(0);
            if (sale.getId() == null) {
                sale.setId(500L);
                for (com.bms.entity.SaleItem item : sale.getItems()) {
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

    private SaleCreateRequest request(List<SaleCreateRequest.SaleItemRequest> items) {
        SaleCreateRequest request = new SaleCreateRequest();
        request.setItems(items);
        return request;
    }

    private SaleCreateRequest.SaleItemRequest line(Long productId, int quantity) {
        SaleCreateRequest.SaleItemRequest item = new SaleCreateRequest.SaleItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        return item;
    }

    @Test
    void twoLinesOfFivePriceTheSameAsOneLineOfTenWhenTierCrosses() {
        // Tiered promotion: qty >= 10 gets 10% off. Two lines of 5 each would each
        // miss the tier if priced separately, but merged at 10 they cross it.
        Product product = product(1L, "100.00", "40.00");

        // Promotion mock: 10% discount when quantity >= 10, none below.
        when(promotionService.calculateBestPrice(any(Product.class), anyInt(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Product p = inv.getArgument(0);
                    int qty = inv.getArgument(1);
                    BigDecimal unit = p.getUnitPrice();
                    BigDecimal discount = qty >= 10 ? unit.multiply(new BigDecimal("0.10")) : BigDecimal.ZERO;
                    return new PricingQuote(unit, unit.subtract(discount), discount,
                            null, null, null, null, qty >= 10 ? "PROMOTION" : "BASE");
                });

        // Two separate lines of qty=5 each.
        SaleCreateRequest separate = request(List.of(line(1L, 5), line(1L, 5)));
        SaleResponse separateResult = saleService.createSale(separate, 9L);

        // One merged line of qty=10.
        Product product2 = product(2L, "100.00", "40.00");
        SaleCreateRequest merged = request(List.of(line(2L, 10)));
        SaleResponse mergedResult = saleService.createSale(merged, 9L);

        // Both produce the same total.
        assertEquals(0, separateResult.getTotalAmount().compareTo(mergedResult.getTotalAmount()),
                "merged and separate lines must produce the same total");

        // Both produce a single SaleItem (merged by product).
        assertEquals(1, separateResult.getItems().size(),
                "two lines of the same product merge into one SaleItem");
        assertEquals(1, mergedResult.getItems().size(),
                "one line produces one SaleItem");
        assertEquals(10, separateResult.getItems().get(0).getQuantity(),
                "merged SaleItem carries the combined quantity");
        assertEquals(10, mergedResult.getItems().get(0).getQuantity());

        // The tier discount is applied (10% of 1000 = 100 off).
        assertTrue(separateResult.getTotalAmount().compareTo(new BigDecimal("1000.00")) < 0,
                "tier discount should reduce the total");
    }
}
