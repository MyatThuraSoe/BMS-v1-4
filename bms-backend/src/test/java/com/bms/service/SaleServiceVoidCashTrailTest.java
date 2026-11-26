package com.bms.service;

import com.bms.entity.ArPayment;
import com.bms.entity.CashDrawer;
import com.bms.entity.InventoryLedger;
import com.bms.entity.CashDrawerMovement;
import com.bms.entity.CashDrawerSession;
import com.bms.entity.CashShift;
import com.bms.entity.Customer;
import com.bms.entity.DrawerMovementType;
import com.bms.entity.DrawerSessionStatus;
import com.bms.entity.PaymentMethod;
import com.bms.entity.PaymentStatus;
import com.bms.entity.Product;
import com.bms.entity.Role;
import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
import com.bms.entity.SalePayment;
import com.bms.entity.ShiftType;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.repository.ArPaymentRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6: voids must always leave a cash trail.
 * Acceptance: voiding yesterday's cash sale produces a paid-out on today's
 * drawer referencing the original sale, and the closed drawer's totals are
 * unchanged. Reason required; manager window 24h (D5); no open drawer -> reject.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SaleServiceVoidCashTrailTest {

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
    @Mock private ArPaymentRepository arPaymentRepository;
    @Mock private PeriodGuard periodGuard;
    @Mock private LedgerPostingService ledgerPostingService;
    @Mock private SystemSettingRepository systemSettingRepository;

    private SaleService saleService;

    private User admin;
    private User manager;
    private Product product;
    private CashDrawer currentDrawer;
    private CashDrawerSession todaySession;
    private CashDrawerSession closedSession;
    private Sale sale;
    private SaleItem saleItem;
    private SalePayment cashPayment;

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
        ReflectionTestUtils.setField(saleService, "arPaymentRepository", arPaymentRepository);
        ReflectionTestUtils.setField(saleService, "periodGuard", periodGuard);
        ReflectionTestUtils.setField(saleService, "ledgerPostingService", ledgerPostingService);
        ReflectionTestUtils.setField(saleService, "systemSettingRepository", systemSettingRepository);
        ShopTimeTestSupport.withClock(saleService);

        admin = user(1L, Role.RoleName.ROLE_ADMIN);
        manager = user(2L, Role.RoleName.ROLE_MANAGER);

        product = new Product();
        product.setId(10L);
        product.setSku("SKU-1");
        product.setName("Widget");
        product.setUnitPrice(new BigDecimal("100.00"));
        product.setCostPrice(new BigDecimal("60.00"));
        product.setStockQuantity(5);

        currentDrawer = new CashDrawer();
        currentDrawer.setId(70L);
        currentDrawer.setName("Main Drawer");
        currentDrawer.setCode("MAIN");

        todaySession = new CashDrawerSession();
        todaySession.setId(80L);
        todaySession.setDrawer(currentDrawer);
        todaySession.setStatus(DrawerSessionStatus.OPEN);
        todaySession.setOpenedAt(LocalDateTime.now().minusHours(1));

        closedSession = new CashDrawerSession();
        closedSession.setId(81L);
        closedSession.setDrawer(currentDrawer);
        closedSession.setStatus(DrawerSessionStatus.CLOSED);
        closedSession.setOpenedAt(LocalDateTime.now().minusDays(2));
        closedSession.setClosedAt(LocalDateTime.now().minusDays(1));

        sale = new Sale();
        sale.setId(100L);
        sale.setInvoiceNumber("INV-VOID-1");
        sale.setCashierId(1L);
        sale.setSaleDate(LocalDateTime.now().minusDays(1));
        sale.setSubtotal(new BigDecimal("100.00"));
        sale.setTaxAmount(BigDecimal.ZERO);
        sale.setTotalAmount(new BigDecimal("100.00"));
        sale.setAmountPaid(new BigDecimal("100.00"));
        sale.setSaleType(Sale.SaleType.CASH);
        sale.setPaymentMethod(Sale.PaymentMethod.CASH);
        sale.setPaymentStatus(Sale.PaymentStatus.PAID);

        saleItem = new SaleItem();
        saleItem.setId(1000L);
        saleItem.setSale(sale);
        saleItem.setProduct(product);
        saleItem.setQuantity(1);
        saleItem.setQuantityRefunded(0);
        saleItem.setUnitPrice(new BigDecimal("100.00"));
        saleItem.setTotalPrice(new BigDecimal("100.00"));
        saleItem.setCostPriceAtSale(new BigDecimal("60.00"));
        sale.getItems().add(saleItem);

        CashShift originalShift = new CashShift();
        originalShift.setId(90L);
        originalShift.setShiftType(ShiftType.CASH_DRAWER);
        originalShift.setDrawerSession(closedSession);
        sale.setEmployeeShift(originalShift);

        cashPayment = new SalePayment();
        cashPayment.setId(500L);
        cashPayment.setSale(sale);
        cashPayment.setPaymentMethod(PaymentMethod.CASH);
        cashPayment.setAmount(new BigDecimal("100.00"));
        cashPayment.setPaymentStatus(PaymentStatus.COMPLETED);
        cashPayment.setPaidAt(sale.getSaleDate());

        lenient().when(saleRepository.findById(100L)).thenReturn(Optional.of(sale));
        lenient().when(saleRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(sale));
        lenient().when(saleRepository.save(any(Sale.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        lenient().when(userRepository.findById(2L)).thenReturn(Optional.of(manager));
        lenient().when(salePaymentRepository.findBySaleIdOrderByPaidAtAsc(100L))
                .thenReturn(List.of(cashPayment));
        lenient().when(saleReturnRepository.sumRefundedAmountBySaleId(100L)).thenReturn(null);
        lenient().when(arPaymentRepository.findByInvoiceIdOrderByPaymentDateAsc(100L)).thenReturn(List.of());
        lenient().when(saleItemRepository.findByIdForUpdate(1000L)).thenReturn(Optional.of(saleItem));
        lenient().when(productRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(product));
        lenient().when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(stockMovementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(saleReturnService.findBySaleId(100L)).thenReturn(List.of());
        CashShift ownShift = new CashShift();
        ownShift.setId(91L);
        ownShift.setShiftType(ShiftType.CASH_DRAWER);
        ownShift.setDrawerSession(todaySession);
        lenient().when(cashShiftRepository.findByCashierIdAndStatus(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(cashShiftRepository.findByCashierIdAndStatus(1L, "OPEN"))
                .thenReturn(Optional.of(ownShift));
        lenient().when(cashDrawerSessionService.getActiveDrawers()).thenReturn(List.of(currentDrawer));
        lenient().when(cashDrawerSessionService.getOpenSession(70L)).thenReturn(todaySession);
        lenient().when(cashDrawerSessionService.recordFinancialMovement(
                any(), any(), any(), anyString(), anyLong(), any(), any(), anyString()))
                .thenAnswer(inv -> {
                    CashDrawerMovement movement = new CashDrawerMovement();
                    movement.setDrawerSession(inv.getArgument(0));
                    movement.setMovementType(inv.getArgument(1));
                    movement.setAmount(inv.getArgument(2));
                    movement.setReferenceType(inv.getArgument(3));
                    movement.setReferenceId(inv.getArgument(4));
                    movement.setReason(inv.getArgument(7));
                    return movement;
                });
    }

    private User user(Long id, Role.RoleName roleName) {
        Role role = new Role();
        role.setId(id);
        role.setName(roleName);
        User user = new User();
        user.setId(id);
        user.setUsername(roleName.name().toLowerCase());
        user.setFirstName("Test");
        user.setLastName("User");
        user.setRole(role);
        return user;
    }

    @Test
    void voidWithoutReasonIsRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> saleService.voidSale(100L, 1L, "   "));
        assertTrue(ex.getMessage().toLowerCase().contains("reason"));
        assertFalse(sale.getIsVoided());
        verify(cashDrawerSessionService, never()).recordFinancialMovement(
                any(), any(), any(), anyString(), anyLong(), any(), any(), anyString());
    }

    @Test
    void managerCannotVoidOutside24HourWindow() {
        sale.setSaleDate(LocalDateTime.now().minusHours(25));
        // Even with the original drawer still open, the window must block it.
        sale.getEmployeeShift().setDrawerSession(todaySession);

        assertThrows(BusinessException.class,
                () -> saleService.voidSale(100L, 2L, "Customer changed mind"));
        assertFalse(sale.getIsVoided());
    }

    @Test
    void managerCanVoidWithinWindow() {
        sale.setSaleDate(LocalDateTime.now().minusHours(2));
        sale.getEmployeeShift().setDrawerSession(todaySession);

        saleService.voidSale(100L, 2L, "Wrong item rung up");

        assertTrue(sale.getIsVoided());
        verify(cashDrawerSessionService).recordFinancialMovement(
                eq(todaySession), eq(DrawerMovementType.CASH_REFUND),
                eq(new BigDecimal("-100.00")), eq("SALE"), eq(100L),
                any(), any(), anyString());
    }

    @Test
    void voidStillReversesProfitWhenTheProductSoldAgainLater() {
        sale.setSaleDate(LocalDateTime.now().minusHours(2));
        sale.getEmployeeShift().setDrawerSession(todaySession);
        saleItem.setCostPriceAtSale(new BigDecimal("60.00"));
        // Even though the product sold again after this ticket, the void still
        // restores the stock — stock is one aggregate count and the voided units
        // go back on the shelf regardless of later sales.

        saleService.voidSale(100L, 2L, "Customer changed their mind");

        // The financial reversal is unconditional: a voided sale must never keep
        // its revenue or its COGS, whatever sold afterwards.
        verify(ledgerPostingService).postVoidProfit(sale, saleItem, 1, sale.getVoidedAt());
        // Stock is always restored on void (BUG-1).
        assertEquals(6, product.getStockQuantity());
        // The inventory-ledger VOID posting and the ADJUSTMENT_IN movement are saved.
        verify(ledgerPostingService).postInventory(
                any(Product.class), any(InventoryLedger.EventType.class), anyString(), any(),
                any(), anyInt(), any(), any(), any(), anyString(), any(), any());
        verify(stockMovementRepository).save(any());
    }

    @Test
    void lateVoidPostsPaidOutOnCurrentDrawerAndLeavesClosedDrawerUntouched() {
        saleService.voidSale(100L, 1L, "Duplicate receipt");

        assertTrue(sale.getIsVoided());
        assertNotNull(sale.getVoidedAt());
        assertEquals(1L, sale.getVoidedBy());
        assertEquals("Duplicate receipt", sale.getVoidedReason());

        ArgumentCaptor<CashDrawerSession> sessionCaptor = ArgumentCaptor.forClass(CashDrawerSession.class);
        ArgumentCaptor<DrawerMovementType> typeCaptor = ArgumentCaptor.forClass(DrawerMovementType.class);
        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(cashDrawerSessionService).recordFinancialMovement(
                sessionCaptor.capture(), typeCaptor.capture(), amountCaptor.capture(),
                eq("SALE"), eq(100L), any(), any(), reasonCaptor.capture());

        assertEquals(todaySession, sessionCaptor.getValue());
        assertEquals(DrawerMovementType.PAY_OUT, typeCaptor.getValue());
        assertEquals(0, amountCaptor.getValue().compareTo(new BigDecimal("-100.00")));
        assertTrue(reasonCaptor.getValue().toLowerCase().contains("late void"));
        assertTrue(reasonCaptor.getValue().contains("Duplicate receipt"));

        verify(cashDrawerSessionService, never()).recordFinancialMovement(
                eq(closedSession), any(), any(), anyString(), anyLong(), any(), any(), anyString());
        assertEquals(PaymentStatus.REFUNDED, cashPayment.getPaymentStatus());
    }

    @Test
    void lateVoidSubtractsPriorCashRefunds() {
        lenient().when(saleReturnRepository.sumRefundedAmountBySaleId(100L))
                .thenReturn(new BigDecimal("30.00"));

        saleService.voidSale(100L, 1L, "Duplicate receipt");

        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(cashDrawerSessionService).recordFinancialMovement(
                eq(todaySession), eq(DrawerMovementType.PAY_OUT), amountCaptor.capture(),
                eq("SALE"), eq(100L), any(), any(), anyString());
        assertEquals(0, amountCaptor.getValue().compareTo(new BigDecimal("-70.00")));
    }

    @Test
    void lateVoidUsesTheVoidingUsersOwnOpenDrawer() {
        CashDrawer otherDrawer = new CashDrawer();
        otherDrawer.setId(71L);
        CashDrawerSession otherSession = new CashDrawerSession();
        otherSession.setId(82L);
        otherSession.setDrawer(otherDrawer);
        otherSession.setStatus(DrawerSessionStatus.OPEN);
        CashShift ownShift = new CashShift();
        ownShift.setId(91L);
        ownShift.setShiftType(ShiftType.CASH_DRAWER);
        ownShift.setDrawerSession(todaySession);

        when(cashDrawerSessionService.getActiveDrawers()).thenReturn(List.of(otherDrawer, currentDrawer));
        when(cashDrawerSessionService.getOpenSession(70L)).thenReturn(todaySession);
        when(cashShiftRepository.findByCashierIdAndStatus(1L, "OPEN")).thenReturn(Optional.of(ownShift));

        saleService.voidSale(100L, 1L, "Duplicate receipt");

        verify(cashDrawerSessionService).recordFinancialMovement(
                eq(todaySession), eq(DrawerMovementType.PAY_OUT), eq(new BigDecimal("-100.00")),
                eq("SALE"), eq(100L), any(), eq(ownShift), anyString());
        verify(cashDrawerSessionService, never()).recordFinancialMovement(
                eq(otherSession), any(), any(), anyString(), anyLong(), any(), any(), anyString());
    }

    @Test
    void lateVoidRequiresTheVoidingUsersOwnOpenDrawer() {
        when(cashShiftRepository.findByCashierIdAndStatus(1L, "OPEN")).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> saleService.voidSale(100L, 1L, "Duplicate receipt"));
        assertTrue(ex.getMessage().toLowerCase().contains("cash drawer shift"), ex.getMessage());
        assertFalse(sale.getIsVoided());
        verify(cashDrawerSessionService, never()).recordFinancialMovement(
                any(), any(), any(), anyString(), anyLong(), any(), any(), anyString());
    }

    @Test
    void voidWithNoOpenDrawerIsRejected() {
        lenient().when(cashDrawerSessionService.getOpenSession(70L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> saleService.voidSale(100L, 1L, "Duplicate receipt"));
        assertTrue(ex.getMessage().toLowerCase().contains("drawer"));
        assertFalse(sale.getIsVoided());
        verify(cashDrawerSessionService, never()).recordFinancialMovement(
                any(), any(), any(), anyString(), anyLong(), any(), any(), anyString());
    }

    @Test
    void openOriginalDrawerIsStillReversedInPlace() {
        sale.setSaleDate(LocalDateTime.now().minusHours(1));
        sale.getEmployeeShift().setDrawerSession(todaySession);

        saleService.voidSale(100L, 1L, "Wrong item rung up");

        assertTrue(sale.getIsVoided());
        verify(cashDrawerSessionService).recordFinancialMovement(
                eq(todaySession), eq(DrawerMovementType.CASH_REFUND),
                eq(new BigDecimal("-100.00")), eq("SALE"), eq(100L),
                any(), any(), anyString());
        assertEquals(PaymentStatus.REFUNDED, cashPayment.getPaymentStatus());
    }

    @Test
    void voidCreditSaleConvertsNonCashArPaymentsToCustomerCredit() {
        // N3: the customer owes 100 on other invoices and 20 is still unpaid here.
        // Paying 80 of this invoice by card already lowered the balance, so the
        // void must hand that 80 back as credit WITHOUT taking it off the balance
        // a second time.
        Sale sale = creditSale();
        Customer customer = creditCustomer(new BigDecimal("120.00"));
        sale.setCustomer(customer);

        ArPayment arPayment = new ArPayment();
        arPayment.setId(11L);
        arPayment.setInvoice(sale);
        arPayment.setAmount(new BigDecimal("80.00"));
        arPayment.setPaymentMethod(PaymentMethod.CARD);
        arPayment.setPaymentDate(LocalDateTime.now().minusHours(1));
        arPayment.setReversedAt(null);

        lenient().when(saleRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(sale));
        lenient().when(customerRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(customer));
        lenient().when(salePaymentRepository.findBySaleIdOrderByPaidAtAsc(100L)).thenReturn(List.of());
        lenient().when(arPaymentRepository.findByInvoiceIdOrderByPaymentDateAsc(100L))
                .thenReturn(List.of(arPayment));

        saleService.voidSale(100L, 1L, "Credit sale reversed");

        assertNotNull(arPayment.getReversedAt());
        assertEquals(0, customer.getCreditBalance().compareTo(new BigDecimal("80.00")),
                "the card payment comes back as store credit");
        assertEquals(0, customer.getCurrentBalance().compareTo(new BigDecimal("100.00")),
                "only the 20 still owed on this invoice may leave the balance");
    }

    @Test
    void voidCreditSaleRefundsCashArPaymentFromTheDrawer() {
        Sale sale = creditSale();
        Customer customer = creditCustomer(new BigDecimal("120.00"));
        sale.setCustomer(customer);

        ArPayment arPayment = new ArPayment();
        arPayment.setId(12L);
        arPayment.setInvoice(sale);
        arPayment.setAmount(new BigDecimal("80.00"));
        arPayment.setPaymentMethod(PaymentMethod.CASH);
        arPayment.setPaymentDate(LocalDateTime.now().minusHours(1));

        lenient().when(saleRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(sale));
        lenient().when(customerRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(customer));
        lenient().when(salePaymentRepository.findBySaleIdOrderByPaidAtAsc(100L)).thenReturn(List.of());
        lenient().when(arPaymentRepository.findByInvoiceIdOrderByPaymentDateAsc(100L))
                .thenReturn(List.of(arPayment));

        saleService.voidSale(100L, 1L, "Credit sale reversed");

        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(cashDrawerSessionService).recordFinancialMovement(
                eq(todaySession), eq(DrawerMovementType.PAY_OUT), amountCaptor.capture(),
                eq("SALE"), eq(100L), any(), any(), anyString());
        assertEquals(0, amountCaptor.getValue().compareTo(new BigDecimal("-80.00")),
                "a cash payment on the invoice leaves the drawer as cash");
        assertEquals(0, customer.getCreditBalance().compareTo(BigDecimal.ZERO),
                "cash is refunded, not converted to credit");
        assertEquals(0, customer.getCurrentBalance().compareTo(new BigDecimal("100.00")));
    }

    @Test
    void voidingTheSameSaleTwiceIsRejected() {
        sale.setIsVoided(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> saleService.voidSale(100L, 1L, "Duplicate receipt"));
        assertTrue(ex.getMessage().toLowerCase().contains("already voided"), ex.getMessage());
        verify(cashDrawerSessionService, never()).recordFinancialMovement(
                any(), any(), any(), anyString(), anyLong(), any(), any(), anyString());
    }

    /** A 100 credit invoice with 80 paid and 20 still outstanding. */
    private Sale creditSale() {
        sale.setSaleType(Sale.SaleType.CREDIT);
        sale.setPaymentStatus(Sale.PaymentStatus.PARTIAL);
        sale.setAmountPaid(new BigDecimal("80.00"));
        sale.setTotalAmount(new BigDecimal("100.00"));
        sale.setSubtotal(new BigDecimal("100.00"));
        sale.setTaxAmount(BigDecimal.ZERO);
        return sale;
    }

    private Customer creditCustomer(BigDecimal currentBalance) {
        Customer customer = new Customer();
        customer.setId(7L);
        customer.setCustomerCode("CUST-7");
        customer.setFirstName("Alice");
        customer.setLastName("Customer");
        customer.setCurrentBalance(currentBalance);
        customer.setCreditBalance(BigDecimal.ZERO);
        return customer;
    }
}