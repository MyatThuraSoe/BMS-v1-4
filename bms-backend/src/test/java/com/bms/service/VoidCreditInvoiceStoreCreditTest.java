package com.bms.service;

import com.bms.entity.ArPayment;
import com.bms.entity.CashDrawerSession;
import com.bms.entity.CashShift;
import com.bms.entity.Customer;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;

/**
 * Audit #1: the SalePayment(method=CREDIT) row is the invoice's own debt
 * placeholder. Voiding a collected credit invoice must not turn that row into
 * store credit for the customer.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VoidCreditInvoiceStoreCreditTest {

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
    private Sale sale;
    private Customer customer;
    private ArPayment arPayment;
    private SalePayment creditTender;

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
        admin.setFirstName("Void");
        admin.setLastName("Admin");
        admin.setRole(role);
        lenient().when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

        customer = new Customer();
        customer.setId(7L);
        customer.setFirstName("Credit");
        customer.setLastName("Buyer");
        customer.setIsActive(true);
        customer.setCreditBalance(BigDecimal.ZERO.setScale(2));
        customer.setCurrentBalance(new BigDecimal("70.00"));
        lenient().when(customerRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(customer));
        lenient().when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

        CashDrawerSession session = new CashDrawerSession();
        session.setId(80L);
        session.setStatus(DrawerSessionStatus.OPEN);
        CashShift shift = new CashShift();
        shift.setId(90L);
        shift.setShiftType(ShiftType.CASH_DRAWER);
        shift.setDrawerSession(session);

        sale = new Sale();
        sale.setId(100L);
        sale.setInvoiceNumber("CR-AUDIT-1");
        sale.setCashierId(1L);
        sale.setIsActive(true);
        sale.setIsVoided(false);
        sale.setSaleDate(LocalDateTime.now().minusMinutes(5));
        sale.setSaleType(Sale.SaleType.CREDIT);
        sale.setPaymentMethod(Sale.PaymentMethod.CREDIT);
        sale.setPaymentStatus(Sale.PaymentStatus.PAID);
        sale.setReturnStatus(Sale.ReturnStatus.COMPLETED);
        sale.setSubtotal(new BigDecimal("100.00"));
        sale.setTotalAmount(new BigDecimal("100.00"));
        sale.setAmountPaid(new BigDecimal("30.00"));
        sale.setTaxAmount(BigDecimal.ZERO);
        sale.setCustomer(customer);
        sale.setEmployeeShift(shift);
        lenient().when(saleRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(sale));
        lenient().when(saleRepository.save(any(Sale.class))).thenAnswer(inv -> inv.getArgument(0));

        creditTender = new SalePayment();
        creditTender.setId(500L);
        creditTender.setSale(sale);
        creditTender.setPaymentMethod(PaymentMethod.CREDIT);
        creditTender.setPaymentStatus(PaymentStatus.COMPLETED);
        creditTender.setAmount(new BigDecimal("100.00"));
        lenient().when(salePaymentRepository.findBySaleIdOrderByPaidAtAsc(100L))
                .thenReturn(new ArrayList<>(List.of(creditTender)));

        arPayment = new ArPayment();
        arPayment.setId(600L);
        arPayment.setInvoice(sale);
        arPayment.setAmount(new BigDecimal("30.00"));
        arPayment.setPaymentMethod(PaymentMethod.CASH);
        arPayment.setPaymentDate(LocalDateTime.now().minusDays(1));
        lenient().when(arPaymentRepository.findByInvoiceIdOrderByPaymentDateAsc(100L))
                .thenReturn(new ArrayList<>(List.of(arPayment)));
        lenient().when(arPaymentRepository.save(any(ArPayment.class))).thenAnswer(inv -> inv.getArgument(0));

        lenient().when(saleReturnRepository.sumRefundedAmountBySaleId(100L)).thenReturn(BigDecimal.ZERO);
        lenient().when(saleReturnService.findBySaleId(100L)).thenReturn(List.of());
    }

    @Test
    void voidOfCollectedCreditInvoiceDoesNotMintStoreCreditFromTheDebtRow() {
        saleService.voidSale(100L, 1L, "Customer cancelled");

        assertEquals(0, BigDecimal.ZERO.compareTo(customer.getCreditBalance()),
                "the CREDIT tender row is debt, not collected money: " + customer.getCreditBalance());
        assertNotNull(arPayment.getReversedAt(), "the cash collection must still be reversed");
    }
}
