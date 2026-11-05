package com.bms.service;

import com.bms.dto.request.PaymentValidationRequest;
import com.bms.dto.request.SalePaymentRequest;
import com.bms.dto.response.PaymentValidationResponse;
import com.bms.entity.CashDrawer;
import com.bms.entity.CashDrawerSession;
import com.bms.entity.CashShift;
import com.bms.entity.DrawerSessionStatus;
import com.bms.entity.PaymentMethod;
import com.bms.entity.Sale;
import com.bms.entity.ShiftType;
import com.bms.exception.BusinessException;
import com.bms.repository.CashShiftRepository;
import com.bms.repository.SalePaymentRepository;
import com.bms.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Acceptance coverage for PRD §18.1, §18.2 and §18.4: cash rules are decided in
 * one place, before any sale or stock row is written.
 */
@ExtendWith(MockitoExtension.class)
class PaymentValidationServiceTest {

    @Mock
    private CashShiftRepository cashShiftRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SalePaymentRepository salePaymentRepository;

    @Mock
    private CashDrawerSessionService cashDrawerSessionService;

    @InjectMocks
    private PaymentValidationService paymentValidationService;

    private static SalePaymentRequest payment(String method, String amount) {
        SalePaymentRequest request = new SalePaymentRequest();
        request.setMethod(method);
        request.setAmount(new BigDecimal(amount));
        return request;
    }

    private static CashShift shift(ShiftType type, DrawerSessionStatus drawerStatus) {
        CashShift shift = new CashShift();
        shift.setId(10L);
        shift.setCashierId(1L);
        shift.setShiftType(type);
        if (drawerStatus != null) {
            CashDrawer drawer = new CashDrawer();
            drawer.setId(1L);
            drawer.setName("Main Drawer");
            drawer.setCode("MAIN");

            CashDrawerSession session = new CashDrawerSession();
            session.setId(100L);
            session.setDrawer(drawer);
            session.setStatus(drawerStatus);
            shift.setDrawerSession(session);
        }
        return shift;
    }

    // §18.1 Non-cash employee can sell without a drawer
    @Test
    void nonCashShiftAllowsWalletWithoutDrawer() {
        PaymentValidationResponse response = paymentValidationService.validate(
                shift(ShiftType.NON_CASH, null),
                List.of(payment("MOBILE_WALLET", "25000")));

        assertTrue(response.isValid());
        assertFalse(response.isCashAllowed());
        assertFalse(response.isHasActiveDrawerSession());
        assertNull(response.getDrawerSessionId());
        assertEquals("NON_CASH", response.getShiftType());
    }

    // §18.2 Non-cash employee cannot accept cash
    @Test
    void nonCashShiftRejectsCash() {
        PaymentValidationResponse response = paymentValidationService.validate(
                shift(ShiftType.NON_CASH, null),
                List.of(payment("CASH", "25000")));

        assertFalse(response.isValid());
        assertEquals("CASH_NOT_ALLOWED_FOR_SHIFT", response.getCode());
        assertFalse(response.isCashAllowed());
    }

    // §18.4 20,000 cash + 30,000 wallet on a non-cash shift must be blocked
    @Test
    void mixedPaymentWithCashIsRejectedOnNonCashShift() {
        PaymentValidationResponse response = paymentValidationService.validate(
                shift(ShiftType.NON_CASH, null),
                List.of(payment("CASH", "20000"), payment("MOBILE_WALLET", "30000")));

        assertFalse(response.isValid());
        assertEquals("CASH_NOT_ALLOWED_FOR_SHIFT", response.getCode());
    }

    @Test
    void mixedPaymentIsAllowedOnOpenCashDrawer() {
        PaymentValidationResponse response = paymentValidationService.validate(
                shift(ShiftType.CASH_DRAWER, DrawerSessionStatus.OPEN),
                List.of(payment("CASH", "20000"), payment("MOBILE_WALLET", "30000")));

        assertTrue(response.isValid());
        assertTrue(response.isCashAllowed());
        assertTrue(response.isHasActiveDrawerSession());
        assertEquals(100L, response.getDrawerSessionId());
        assertEquals("Main Drawer", response.getDrawerName());
    }

    @Test
    void cashDrawerShiftWithoutDrawerRejectsCash() {
        PaymentValidationResponse response = paymentValidationService.validate(
                shift(ShiftType.CASH_DRAWER, null),
                List.of(payment("CASH", "10000")));

        assertFalse(response.isValid());
        assertEquals("DRAWER_SESSION_REQUIRED", response.getCode());
    }

    @Test
    void closedDrawerRejectsCash() {
        PaymentValidationResponse response = paymentValidationService.validate(
                shift(ShiftType.CASH_DRAWER, DrawerSessionStatus.CLOSED),
                List.of(payment("CASH", "10000")));

        assertFalse(response.isValid());
        assertEquals("DRAWER_SESSION_REQUIRED", response.getCode());
    }

    @Test
    void verifyForSaleRejectsMismatchedTotal() {
        BusinessException exception = assertThrows(BusinessException.class, () ->
                paymentValidationService.verifyForSale(
                        shift(ShiftType.CASH_DRAWER, DrawerSessionStatus.OPEN),
                        List.of(payment("CASH", "40000")),
                        new BigDecimal("50000"),
                        Sale.SaleType.CASH));

        assertTrue(exception.getMessage().startsWith("PAYMENT_TOTAL_MISMATCH"));
    }

    @Test
    void verifyForSaleAcceptsExactSplitTotal() {
        paymentValidationService.verifyForSale(
                shift(ShiftType.CASH_DRAWER, DrawerSessionStatus.OPEN),
                List.of(payment("CASH", "20000"), payment("MOBILE_WALLET", "30000")),
                new BigDecimal("50000"),
                Sale.SaleType.CASH);
    }

    @Test
    void creditLineIsOnlyAllowedOnCreditSales() {
        BusinessException exception = assertThrows(BusinessException.class, () ->
                paymentValidationService.verifyForSale(
                        shift(ShiftType.NON_CASH, null),
                        List.of(payment("CREDIT", "50000")),
                        new BigDecimal("50000"),
                        Sale.SaleType.CASH));

        assertTrue(exception.getMessage().startsWith("PAYMENT_INVALID"));
    }

    @Test
    void creditSaleMustUseCreditLine() {
        BusinessException exception = assertThrows(BusinessException.class, () ->
                paymentValidationService.verifyForSale(
                        shift(ShiftType.NON_CASH, null),
                        List.of(payment("MOBILE_WALLET", "50000")),
                        new BigDecimal("50000"),
                        Sale.SaleType.CREDIT));

        assertTrue(exception.getMessage().startsWith("PAYMENT_INVALID"));
    }

    @Test
    void legacyCashSaleStillBecomesOneCashPayment() {
        List<SalePaymentRequest> payments = paymentValidationService.resolvePayments(
                null, new BigDecimal("50000"), Sale.SaleType.CASH);

        assertEquals(1, payments.size());
        assertEquals("CASH", payments.get(0).getMethod());
        assertEquals(0, payments.get(0).getAmount().compareTo(new BigDecimal("50000")));
    }

    @Test
    void legacyCreditSaleStillBecomesOneCreditPayment() {
        List<SalePaymentRequest> payments = paymentValidationService.resolvePayments(
                null, new BigDecimal("50000"), Sale.SaleType.CREDIT);

        assertEquals(1, payments.size());
        assertEquals("CREDIT", payments.get(0).getMethod());
    }

    @Test
    void walletAliasesNormaliseToMobileWallet() {
        assertEquals(PaymentMethod.MOBILE_WALLET, paymentValidationService.parseMethod("KBZPAY"));
        assertEquals(PaymentMethod.MOBILE_WALLET, paymentValidationService.parseMethod("WAVEPAY"));
        assertEquals(PaymentMethod.MOBILE_WALLET, paymentValidationService.parseMethod("WALLET"));
        assertEquals(PaymentMethod.BANK_TRANSFER, paymentValidationService.parseMethod("BANK"));
    }

    @Test
    void requireOpenShiftFailsWithNoOpenShift() {
        when(cashShiftRepository.findByCashierIdAndStatus(1L, "OPEN"))
                .thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentValidationService.requireOpenShift(1L));

        assertTrue(exception.getMessage().startsWith("NO_OPEN_SHIFT"));
    }

    @Test
    void validateForUserReportsMissingShift() {
        when(cashShiftRepository.findByCashierIdAndStatus(7L, "OPEN"))
                .thenReturn(Optional.empty());

        PaymentValidationRequest request = new PaymentValidationRequest();
        request.setPayments(List.of(payment("MOBILE_WALLET", "1000")));

        PaymentValidationResponse response =
                paymentValidationService.validateForUser(7L, request);

        assertNotNull(response);
        assertFalse(response.isValid());
        assertEquals("NO_OPEN_SHIFT", response.getCode());
    }
}
