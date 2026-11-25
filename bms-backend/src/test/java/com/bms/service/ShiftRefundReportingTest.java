package com.bms.service;

import com.bms.dto.response.CashShiftResponse;
import com.bms.entity.CashShift;
import com.bms.entity.DrawerMovementType;
import com.bms.entity.ShiftType;
import com.bms.repository.CashDrawerMovementRepository;
import com.bms.repository.CashDrawerSessionRepository;
import com.bms.repository.CashShiftRepository;
import com.bms.repository.SalePaymentRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.SaleReturnRepository;
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
import static org.mockito.Mockito.when;

/**
 * Audit 18: a shift's returns total is the money actually handed back
 * (SaleReturn.refundAmount), not the gross return value which also includes
 * store-credit debt reduction. Here the return rows total 100.00, of which
 * 60.00 was refunded and 40.00 only reduced a credit balance. A non-cash shift
 * has no cash-drawer refund override, so the response must report 60.00.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShiftRefundReportingTest {

    @Mock private ShopTimeService shopTimeService;
    @Mock private CashShiftRepository cashShiftRepository;
    @Mock private SaleRepository saleRepository;
    @Mock private SalePaymentRepository salePaymentRepository;
    @Mock private SaleReturnRepository saleReturnRepository;
    @Mock private CashDrawerMovementRepository cashDrawerMovementRepository;
    @Mock private CashDrawerSessionRepository cashDrawerSessionRepository;
    @Mock private UserRepository userRepository;
    @Mock private CashDrawerSessionService cashDrawerSessionService;
    @Mock private ManagerApprovalService managerApprovalService;
    @Mock private AuditLogService auditLogService;
    @Mock private SystemSettingService systemSettingService;

    private CashShiftService service;

    @BeforeEach
    void setUp() {
        service = new CashShiftService();
        ReflectionTestUtils.setField(service, "shopTimeService", shopTimeService);
        ReflectionTestUtils.setField(service, "cashShiftRepository", cashShiftRepository);
        ReflectionTestUtils.setField(service, "saleRepository", saleRepository);
        ReflectionTestUtils.setField(service, "salePaymentRepository", salePaymentRepository);
        ReflectionTestUtils.setField(service, "saleReturnRepository", saleReturnRepository);
        ReflectionTestUtils.setField(service, "cashDrawerMovementRepository", cashDrawerMovementRepository);
        ReflectionTestUtils.setField(service, "cashDrawerSessionRepository", cashDrawerSessionRepository);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "cashDrawerSessionService", cashDrawerSessionService);
        ReflectionTestUtils.setField(service, "managerApprovalService", managerApprovalService);
        ReflectionTestUtils.setField(service, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(service, "systemSettingService", systemSettingService);
    }

    @Test
    void nonCashShiftReportsRefundedMoneyInsteadOfGrossReturnValue() {
        CashShift shift = new CashShift();
        shift.setId(10L);
        shift.setCashierId(1L);
        shift.setShiftType(ShiftType.NON_CASH);
        shift.setStatus("CLOSED");
        shift.setOpeningAmount(BigDecimal.ZERO);
        shift.setOpeningTime(LocalDateTime.of(2026, 11, 25, 9, 0));

        when(cashShiftRepository.findById(10L)).thenReturn(Optional.of(shift));
        when(userRepository.findById(1L)).thenReturn(Optional.empty());
        when(systemSettingService.cashVarianceTolerance()).thenReturn(BigDecimal.ZERO);
        when(saleRepository.findByEmployeeShiftId(10L)).thenReturn(List.of());
        when(saleRepository.findByCashShiftId(10L)).thenReturn(List.of());
        when(salePaymentRepository.summariseByEmployeeShift(10L)).thenReturn(List.of());
        when(cashDrawerMovementRepository.sumAmountByEmployeeShiftAndType(
                10L, DrawerMovementType.CASH_SALE)).thenReturn(BigDecimal.ZERO);
        when(cashDrawerMovementRepository.sumAmountByEmployeeShiftAndType(
                10L, DrawerMovementType.CASH_REFUND)).thenReturn(BigDecimal.ZERO);
        // Existing repository method: SUM(totalReturnAmount) = 60 refunded + 40 store-credit debt reduction.
        when(saleReturnRepository.sumTotalByEmployeeShiftId(10L)).thenReturn(new BigDecimal("100.00"));

        CashShiftResponse response = service.getShiftById(10L);

        assertEquals(0, new BigDecimal("60.00").compareTo(response.getReturnsTotal()),
                "expected 60.00 actually returned but was " + response.getReturnsTotal());
    }
}
