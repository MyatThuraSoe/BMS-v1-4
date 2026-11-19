package com.bms.service;

import com.bms.dto.request.DrawerMovementRequest;
import com.bms.dto.response.DrawerSessionResponse;
import com.bms.entity.CashDrawerMovement;
import com.bms.entity.CashDrawerSession;
import com.bms.entity.DrawerMovementType;
import com.bms.entity.DrawerSessionStatus;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.repository.CashDrawerMovementRepository;
import com.bms.repository.CashDrawerRepository;
import com.bms.repository.CashDrawerSessionRepository;
import com.bms.repository.CashShiftRepository;
import com.bms.repository.SystemSettingRepository;
import com.bms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The blind count is the control that catches a shortage, so a cashier must not be
 * able to read the expected figure out of the same session response before counting.
 */
@ExtendWith(MockitoExtension.class)
class CashDrawerSessionServiceTest {

    @Mock private CashDrawerRepository cashDrawerRepository;
    @Mock private CashDrawerSessionRepository cashDrawerSessionRepository;
    @Mock private CashDrawerMovementRepository cashDrawerMovementRepository;
    @Mock private CashShiftRepository cashShiftRepository;
    @Mock private UserRepository userRepository;
    @Mock private SystemSettingRepository systemSettingRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private SystemSettingService systemSettingService;

    private CashDrawerSessionService service;

    @BeforeEach
    void setUp() {
        service = new CashDrawerSessionService();
        ReflectionTestUtils.setField(service, "cashDrawerRepository", cashDrawerRepository);
        ReflectionTestUtils.setField(service, "cashDrawerSessionRepository", cashDrawerSessionRepository);
        ReflectionTestUtils.setField(service, "cashDrawerMovementRepository", cashDrawerMovementRepository);
        ReflectionTestUtils.setField(service, "cashShiftRepository", cashShiftRepository);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "systemSettingRepository", systemSettingRepository);
        ReflectionTestUtils.setField(service, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(service, "systemSettingService", systemSettingService);
    }

    @Test
    void aBlindCloseDoesNotHandBackTheExpectedFigure() {
        CashDrawerSession session = openSession(true);
        when(cashDrawerSessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(cashDrawerMovementRepository.sumAmountBySession(1L)).thenReturn(new BigDecimal("1250.00"));
        when(cashDrawerMovementRepository.summariseByType(1L)).thenReturn(List.<Object[]>of(
                new Object[]{DrawerMovementType.CASH_SALE, new BigDecimal("1500.00")},
                new Object[]{DrawerMovementType.CASH_REFUND, new BigDecimal("-450.00")}));
        when(cashDrawerMovementRepository
                .findByDrawerSessionIdOrderByOccurredAtAscIdAsc(1L))
                .thenReturn(List.of(movement(DrawerMovementType.CASH_SALE, "1500.00")));

        DrawerSessionResponse response = service.getSessionResponse(1L);

        assertNull(response.getExpectedCash(), "the expectation must stay hidden until the count is in");
        assertTrue(response.getExpectedHidden());
        assertNull(response.getCashSales(), "per-type totals add back up to the expectation");
        assertNull(response.getCashRefunds());
        assertNull(response.getSafeDrops());
        assertNull(response.getPayIns());
        assertNull(response.getPayOuts());
        assertNull(response.getAdjustments());
        assertNull(response.getMovementBreakdown());
        assertEquals(0, response.getMovementCount());
        assertTrue(response.getMovements().isEmpty(), "movement amounts leak the same figure");
    }

    @Test
    void withoutBlindCloseTheCashierStillSeesTheExpectation() {
        CashDrawerSession session = openSession(false);
        when(cashDrawerSessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(cashDrawerMovementRepository.sumAmountBySession(1L)).thenReturn(new BigDecimal("1250.00"));
        when(cashDrawerMovementRepository.summariseByType(1L)).thenReturn(List.<Object[]>of(
                new Object[]{DrawerMovementType.CASH_SALE, new BigDecimal("1500.00")}));
        when(cashDrawerMovementRepository
                .findByDrawerSessionIdOrderByOccurredAtAscIdAsc(1L))
                .thenReturn(List.of(movement(DrawerMovementType.CASH_SALE, "1500.00")));

        DrawerSessionResponse response = service.getSessionResponse(1L);

        assertEquals(0, response.getExpectedCash().compareTo(new BigDecimal("1250.00")));
        assertEquals(0, response.getCashSales().compareTo(new BigDecimal("1500.00")));
        assertEquals(1, response.getMovements().size());
    }

    @Test
    void aSafeDropWithoutAReasonIsRejected() {
        CashDrawerSession session = openSession(false);
        when(cashDrawerSessionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(session));
        User cashier = new User();
        cashier.setId(9L);
        when(userRepository.findById(9L)).thenReturn(Optional.of(cashier));
        lenient().when(cashShiftRepository.findByCashierIdAndStatus(anyLong(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());

        DrawerMovementRequest request = new DrawerMovementRequest();
        request.setMovementType("SAFE_DROP");
        request.setAmount(new BigDecimal("300.00"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.performOperation(1L, request, 9L, true));
        assertTrue(ex.getMessage().toLowerCase().contains("reason"),
                "a safe drop lowers expected cash, so it needs the same justification as a payout");
    }

    private CashDrawerSession openSession(boolean blindClose) {
        CashDrawerSession session = new CashDrawerSession();
        session.setId(1L);
        session.setStatus(DrawerSessionStatus.OPEN);
        session.setBlindClose(blindClose);
        session.setOpeningFloat(new BigDecimal("200.00"));
        session.setOpenedAt(LocalDateTime.now());
        return session;
    }

    private CashDrawerMovement movement(DrawerMovementType type, String amount) {
        CashDrawerMovement movement = new CashDrawerMovement();
        movement.setId(5L);
        movement.setMovementType(type);
        movement.setAmount(new BigDecimal(amount));
        movement.setOccurredAt(LocalDateTime.now());
        return movement;
    }
}
