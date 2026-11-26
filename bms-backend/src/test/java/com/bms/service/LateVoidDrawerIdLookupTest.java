package com.bms.service;

import com.bms.entity.CashDrawer;
import com.bms.entity.CashDrawerSession;
import com.bms.entity.CashShift;
import com.bms.entity.DrawerMovementType;
import com.bms.entity.DrawerSessionStatus;
import com.bms.entity.PaymentMethod;
import com.bms.entity.PaymentStatus;
import com.bms.entity.Role;
import com.bms.entity.Sale;
import com.bms.entity.SalePayment;
import com.bms.entity.ShiftType;
import com.bms.entity.User;
import com.bms.repository.ArPaymentRepository;
import com.bms.repository.CashShiftRepository;
import com.bms.repository.CustomerRepository;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * Audit #2: CashDrawerSessionService.getOpenSession() takes a drawer ID. The
 * late-void path must not query it with the drawer-session ID, otherwise a
 * perfectly open drawer is reported closed (or the wrong drawer is debited).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LateVoidDrawerIdLookupTest {

    @Mock private SaleRepository saleRepository;
    @Mock private ProductRepository productRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private StockMovementRepository stockMovementRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private UserRepository userRepository;
    @Mock private SaleReturnService saleReturnService;
    @Mock private SaleItemRepository saleItemRepository;
    @Mock private CashShiftRepository cashShiftRepository;
    @Mock private CashDrawerSessionService cashDrawerSessionService;
    @Mock private SalePaymentRepository salePaymentRepository;
    @Mock private SaleReturnRepository saleReturnRepository;
    @Mock private ArPaymentRepository arPaymentRepository;
    @Mock private PeriodGuard periodGuard;
    @Mock private LedgerPostingService ledgerPostingService;
    @Mock private PaymentValidationService paymentValidationService;

    private SaleService saleService;
    private CashDrawerSession todaySession;

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
        ReflectionTestUtils.setField(saleService, "cashDrawerSessionService", cashDrawerSessionService);
        ReflectionTestUtils.setField(saleService, "salePaymentRepository", salePaymentRepository);
        ReflectionTestUtils.setField(saleService, "saleReturnRepository", saleReturnRepository);
        ReflectionTestUtils.setField(saleService, "arPaymentRepository", arPaymentRepository);
        ReflectionTestUtils.setField(saleService, "periodGuard", periodGuard);
        ReflectionTestUtils.setField(saleService, "ledgerPostingService", ledgerPostingService);
        ReflectionTestUtils.setField(saleService, "paymentValidationService", paymentValidationService);
        ShopTimeTestSupport.withClock(saleService);

        Role role = new Role();
        role.setName(Role.RoleName.ROLE_ADMIN);
        User admin = new User();
        admin.setId(1L);
        admin.setFirstName("Late");
        admin.setLastName("Void");
        admin.setRole(role);
        lenient().when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

        CashDrawer currentDrawer = new CashDrawer();
        currentDrawer.setId(70L);
        todaySession = new CashDrawerSession();
        todaySession.setId(80L);
        todaySession.setDrawer(currentDrawer);
        todaySession.setStatus(DrawerSessionStatus.OPEN);

        CashShift voidingShift = new CashShift();
        voidingShift.setId(90L);
        voidingShift.setShiftType(ShiftType.CASH_DRAWER);
        voidingShift.setDrawerSession(todaySession);
        lenient().when(cashShiftRepository.findByCashierIdAndStatus(1L, "OPEN"))
                .thenReturn(Optional.of(voidingShift));

        // The production method resolves by drawer id (70), never by session id (80).
        lenient().when(cashDrawerSessionService.getOpenSession(70L)).thenReturn(todaySession);
        lenient().when(cashDrawerSessionService.getOpenSession(80L)).thenReturn(null);

        CashDrawerSession closedSession = new CashDrawerSession();
        closedSession.setId(81L);
        closedSession.setDrawer(currentDrawer);
        closedSession.setStatus(DrawerSessionStatus.CLOSED);

        Sale sale = new Sale();
        sale.setId(100L);
        sale.setInvoiceNumber("LATE-VOID-1");
        sale.setCashierId(1L);
        sale.setIsActive(true);
        sale.setIsVoided(false);
        sale.setSaleDate(LocalDateTime.now().minusDays(1));
        sale.setSaleType(Sale.SaleType.CASH);
        sale.setPaymentMethod(Sale.PaymentMethod.CASH);
        sale.setPaymentStatus(Sale.PaymentStatus.PAID);
        sale.setSubtotal(new BigDecimal("100.00"));
        sale.setTaxAmount(BigDecimal.ZERO);
        sale.setTotalAmount(new BigDecimal("100.00"));
        sale.setAmountPaid(new BigDecimal("100.00"));
        CashShift originalShift = new CashShift();
        originalShift.setId(91L);
        originalShift.setShiftType(ShiftType.CASH_DRAWER);
        originalShift.setDrawerSession(closedSession);
        sale.setEmployeeShift(originalShift);
        lenient().when(saleRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(sale));
        lenient().when(saleRepository.save(any(Sale.class))).thenAnswer(inv -> inv.getArgument(0));

        SalePayment cashPayment = new SalePayment();
        cashPayment.setId(500L);
        cashPayment.setSale(sale);
        cashPayment.setPaymentMethod(PaymentMethod.CASH);
        cashPayment.setPaymentStatus(PaymentStatus.COMPLETED);
        cashPayment.setAmount(new BigDecimal("100.00"));
        lenient().when(salePaymentRepository.findBySaleIdOrderByPaidAtAsc(100L))
                .thenReturn(List.of(cashPayment));
        lenient().when(arPaymentRepository.findByInvoiceIdOrderByPaymentDateAsc(100L))
                .thenReturn(List.of());
        lenient().when(saleReturnRepository.sumRefundedAmountBySaleId(100L)).thenReturn(BigDecimal.ZERO);
        lenient().when(saleReturnService.findBySaleId(100L)).thenReturn(List.of());
    }

    @Test
    void lateCashVoidLooksUpTheOpenSessionByDrawerId() {
        assertDoesNotThrow(() -> saleService.voidSale(100L, 1L, "Wrong item"));

        verify(cashDrawerSessionService).getOpenSession(70L);
        verify(cashDrawerSessionService).recordFinancialMovement(
                eq(todaySession), eq(DrawerMovementType.PAY_OUT),
                eq(new BigDecimal("-100.00")), eq("SALE"), eq(100L),
                any(), any(), anyString());
    }
}
