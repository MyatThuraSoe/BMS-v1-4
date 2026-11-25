package com.bms.service;

import com.bms.dto.request.SaleCreateRequest;
import com.bms.dto.request.SalePaymentRequest;
import com.bms.entity.CashShift;
import com.bms.entity.Customer;
import com.bms.entity.PaymentMethod;
import com.bms.entity.Product;
import com.bms.entity.Role;
import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
import com.bms.entity.ShopInfo;
import com.bms.entity.User;
import com.bms.exception.InsufficientCreditException;
import com.bms.exception.ResourceNotFoundException;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Defect 1: a zero-total credit sale (100% discount, zero tax) must be marked PAID
 * instead of being left UNPAID, and the credit-limit check must not run when no debt
 * is added on a $0 invoice.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SaleServiceZeroTotalCreditTest {

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
    private Customer creditCustomer;

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

        creditCustomer = new Customer();
        creditCustomer.setId(100L);
        creditCustomer.setFirstName("Credit");
        creditCustomer.setLastName("Customer");
        creditCustomer.setIsActive(true);
        creditCustomer.setCurrentBalance(new BigDecimal("200.00")); // already over the 100.00 limit
        creditCustomer.setCreditLimit(new BigDecimal("100.00"));

        ShopInfo shopInfo = new ShopInfo();
        shopInfo.setTaxPercentage(BigDecimal.ZERO);
        shopInfo.setDiscountEnabled(false);
        lenient().when(shopInfoRepository.findTopByOrderByIdAsc()).thenReturn(Optional.of(shopInfo));

        lenient().when(sequenceService.nextInvoiceNumber()).thenReturn("INV-ZTC-0001");
        lenient().when(sequenceService.nextCreditInvoiceNumber()).thenReturn("CR-ZTC-0001");
        CashShift shift = new CashShift();
        shift.setId(1L);
        lenient().when(paymentValidationService.requireOpenShift(anyLong())).thenReturn(shift);
        lenient().when(paymentValidationService.findOpenShift(anyLong())).thenReturn(shift);
        lenient().when(paymentValidationService.parseMethod(anyString())).thenReturn(PaymentMethod.CASH);
        lenient().when(paymentValidationService.resolvePayments(any(), any(), any()))
                .thenAnswer(inv -> {
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
        lenient().when(customerRepository.findByIdForUpdate(anyLong()))
                .thenReturn(Optional.of(creditCustomer));
        lenient().when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

        lenient().when(userRepository.findById(anyLong())).thenReturn(Optional.of(user(9L, "cashier", null)));
    }

    @Test
    void zeroTotalCreditSaleIsPaidAndCreditLimitIsNotChecked() {
        // Customer is already over their credit limit, but the invoice totals $0.00 so
        // no debt is added: the sale must succeed and be marked PAID.
        product(1L, "0.00", "0.00");
        SaleCreateRequest request = request(null, 1L, 1);
        request.setSaleType("CREDIT");
        request.setCustomerId(100L);
        request.setDueDate(LocalDate.now().plusDays(1));

        saleService.createSale(request, 9L);

        // If the credit-limit check ran on a $0 invoice it would throw; reaching here
        // means it was correctly skipped.
        verify(saleRepository, times(1)).save(any(Sale.class));
    }

    @Test
    void zeroTotalCreditSaleCapturesPaidStatus() {
        product(1L, "0.00", "0.00");
        SaleCreateRequest request = request(null, 1L, 1);
        request.setSaleType("CREDIT");
        request.setCustomerId(100L);
        request.setDueDate(LocalDate.now().plusDays(1));

        saleService.createSale(request, 9L);

        ArgumentCaptor<Sale> captor = ArgumentCaptor.forClass(Sale.class);
        verify(saleRepository).save(captor.capture());
        assertEquals(Sale.PaymentStatus.PAID, captor.getValue().getPaymentStatus(),
                "a zero-total credit invoice owes nothing and must be PAID");
        assertEquals(BigDecimal.ZERO, captor.getValue().getAmountPaid());
    }

    @Test
    void nonZeroCreditSaleStillEnforcesCreditLimit() {
        product(1L, "50.00", "0.00");
        SaleCreateRequest request = request(null, 1L, 1);
        request.setSaleType("CREDIT");
        request.setCustomerId(100L);
        request.setDueDate(LocalDate.now().plusDays(1));

        assertThrows(InsufficientCreditException.class,
                () -> saleService.createSale(request, 9L));
        verify(saleRepository, never()).save(any(Sale.class));
    }

    @Test
    void cashSaleForInactiveCustomerIsRejected() {
        product(1L, "50.00", "0.00");
        Customer inactive = new Customer();
        inactive.setId(101L);
        inactive.setFirstName("Inactive");
        inactive.setLastName("Customer");
        inactive.setIsActive(false);
        inactive.setCurrentBalance(BigDecimal.ZERO);
        inactive.setCreditLimit(BigDecimal.ZERO);
        lenient().when(customerRepository.findById(101L)).thenReturn(Optional.of(inactive));

        SaleCreateRequest request = request(null, 1L, 1);
        request.setCustomerId(101L);
        request.setDueDate(LocalDate.now().plusDays(1));

        assertThrows(ResourceNotFoundException.class,
                () -> saleService.createSale(request, 9L));
        verify(saleRepository, never()).save(any(Sale.class));
    }

    @Test
    void cashSaleForDeletedCustomerIsRejected() {
        product(1L, "50.00", "0.00");
        Customer deleted = new Customer();
        deleted.setId(102L);
        deleted.setFirstName("Deleted");
        deleted.setLastName("Customer");
        deleted.setIsActive(true);
        deleted.setDeletedAt(LocalDateTime.now());
        deleted.setCurrentBalance(BigDecimal.ZERO);
        deleted.setCreditLimit(BigDecimal.ZERO);
        lenient().when(customerRepository.findById(102L)).thenReturn(Optional.of(deleted));

        SaleCreateRequest request = request(null, 1L, 1);
        request.setCustomerId(102L);
        request.setDueDate(LocalDate.now().plusDays(1));

        assertThrows(ResourceNotFoundException.class,
                () -> saleService.createSale(request, 9L));
        verify(saleRepository, never()).save(any(Sale.class));
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
}
