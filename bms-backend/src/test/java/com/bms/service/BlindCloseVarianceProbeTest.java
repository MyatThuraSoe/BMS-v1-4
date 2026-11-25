package com.bms.service;

import com.bms.dto.request.CloseDrawerSessionRequest;
import com.bms.dto.response.DrawerSessionResponse;
import com.bms.entity.CashDrawerSession;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit item 5: a blind close must not turn the tolerance error into an oracle
 * that lets a cashier count up to the expected cash. Accept the count, flag it
 * for a manager, and keep the expectation and variance from the cashier.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BlindCloseVarianceProbeTest {

    @Mock private CashDrawerRepository cashDrawerRepository;
    @Mock private CashDrawerSessionRepository cashDrawerSessionRepository;
    @Mock private CashDrawerMovementRepository cashDrawerMovementRepository;
    @Mock private CashShiftRepository cashShiftRepository;
    @Mock private UserRepository userRepository;
    @Mock private SystemSettingRepository systemSettingRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private SystemSettingService systemSettingService;

    private CashDrawerSessionService service;
    private CashDrawerSession session;

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
        ShopTimeTestSupport.withClock(service);

        session = new CashDrawerSession();
        session.setId(1L);
        session.setStatus(DrawerSessionStatus.OPEN);
        session.setBlindClose(true);
        session.setOpeningFloat(new BigDecimal("100.00"));
        session.setOpenedAt(LocalDateTime.now());

        User cashier = new User();
        cashier.setId(9L);
        session.setOpenedBy(cashier);
        when(cashDrawerSessionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(session));
        when(cashDrawerSessionRepository.save(any(CashDrawerSession.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(9L)).thenReturn(Optional.of(cashier));
        when(cashShiftRepository.findByDrawerSessionIdAndStatus(1L, "OPEN")).thenReturn(List.of());
        when(systemSettingService.cashVarianceTolerance()).thenReturn(new BigDecimal("5.00"));
        when(cashDrawerMovementRepository.sumAmountBySession(1L)).thenReturn(new BigDecimal("100.00"));
        when(cashDrawerMovementRepository.summariseByType(1L)).thenReturn(List.of());
        when(cashDrawerMovementRepository.summariseByEmployee(1L)).thenReturn(List.of());
        when(cashDrawerMovementRepository.findByDrawerSessionIdOrderByOccurredAtAscIdAsc(1L))
                .thenReturn(List.of());
    }

    @Test
    void blindCloseWithAnOutOfToleranceCountIsAcceptedAndFlaggedForReview() {
        CloseDrawerSessionRequest request = new CloseDrawerSessionRequest();
        request.setCountedCash(new BigDecimal("130.00"));

        DrawerSessionResponse response = service.closeSession(1L, request, 9L, false);

        assertEquals(DrawerSessionStatus.CLOSED, session.getStatus());
        assertTrue(session.getClosingNote().toLowerCase().contains("review"),
                "the out-of-tolerance blind close must be flagged for a manager");
        assertNull(response.getExpectedCash(), "the cashier must not learn the expected cash by retrying");
        assertNull(response.getVariance(), "the variance would reveal how close the count was");
        assertNull(response.getCountedCash());

        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).logAction(eq(9L), eq("DRAWER_CLOSE"), description.capture(),
                eq("CashDrawerSession"), eq(1L), eq(null), eq(null));
        assertTrue(description.getValue().toLowerCase().contains("review"),
                "every blind close attempt must be auditable");
    }

    @Test
    void aNonBlindOutOfToleranceCloseIsStillRejected() {
        session.setBlindClose(false);
        CloseDrawerSessionRequest request = new CloseDrawerSessionRequest();
        request.setCountedCash(new BigDecimal("130.00"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.closeSession(1L, request, 9L, false));
        assertTrue(ex.getMessage().toLowerCase().contains("tolerance"));
        assertEquals(DrawerSessionStatus.OPEN, session.getStatus());
    }
}
