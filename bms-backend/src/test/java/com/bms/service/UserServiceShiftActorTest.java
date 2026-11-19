package com.bms.service;

import com.bms.dto.request.CloseShiftRequest;
import com.bms.dto.request.UserUpdateRequest;
import com.bms.entity.CashDrawerSession;
import com.bms.entity.CashShift;
import com.bms.entity.DrawerSessionStatus;
import com.bms.entity.Role;
import com.bms.entity.ShiftType;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.repository.CashDrawerSessionRepository;
import com.bms.repository.CashShiftRepository;
import com.bms.repository.RoleRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * N6: force-closing somebody else's shift is the manager's action, not the
 * cashier's, and it must never strand an open drawer session that nobody else
 * is working.
 */
class UserServiceShiftActorTest {

    private static final Long MANAGER_ID = 7L;
    private static final Long CASHIER_ID = 5L;
    private static final Long SHIFT_ID = 40L;
    private static final Long SESSION_ID = 90L;

    private UserRepository userRepository;
    private CashShiftRepository cashShiftRepository;
    private CashShiftService cashShiftService;
    private AuditLogService auditLogService;
    private UserService service;

    @BeforeEach
    void buildService() {
        userRepository = mock(UserRepository.class);
        RoleRepository roleRepository = mock(RoleRepository.class);
        cashShiftRepository = mock(CashShiftRepository.class);
        cashShiftService = mock(CashShiftService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        auditLogService = mock(AuditLogService.class);
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");

        when(roleRepository.findById(3L)).thenReturn(Optional.of(cashierRole()));

        service = new UserService(userRepository, roleRepository, mock(SaleRepository.class),
                cashShiftRepository, cashShiftService, passwordEncoder, auditLogService, mock(CashDrawerSessionRepository.class));
        ShopTimeService shopTimeService = mock(ShopTimeService.class);
        when(shopTimeService.now()).thenReturn(LocalDateTime.of(2026, 11, 16, 10, 0));
        ReflectionTestUtils.setField(service, "shopTimeService", shopTimeService);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                manager(), null, List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private static Role cashierRole() {
        Role cashier = new Role();
        cashier.setId(3L);
        cashier.setName(Role.RoleName.ROLE_CASHIER);
        return cashier;
    }

    private static User manager() {
        User user = new User();
        user.setId(MANAGER_ID);
        user.setUsername("manager");
        user.setPassword("hash");
        user.setRole(cashierRole());
        user.setIsActive(true);
        return user;
    }

    private static User cashier() {
        User user = new User();
        user.setId(CASHIER_ID);
        user.setUsername("cashier1");
        user.setUsernameNormalized("cashier1");
        user.setEmail("c1@example.com");
        user.setEmailNormalized("c1@example.com");
        user.setPassword("hash");
        user.setRole(cashierRole());
        user.setIsActive(true);
        return user;
    }

    private static UserUpdateRequest deactivateRequest() {
        UserUpdateRequest request = new UserUpdateRequest();
        request.setUsername("cashier1");
        request.setEmail("c1@example.com");
        request.setFirstName("Cash");
        request.setLastName("Ier");
        request.setPhone("0900");
        request.setRoleId(3L);
        request.setIsActive(false);
        request.setCloseOpenShifts(true);
        return request;
    }

    private static CashDrawerSession openSession() {
        CashDrawerSession session = new CashDrawerSession();
        session.setId(SESSION_ID);
        session.setStatus(DrawerSessionStatus.OPEN);
        session.setOpeningFloat(new BigDecimal("5000.00"));
        return session;
    }

    private static CashShift openShift(Long id, CashDrawerSession session) {
        CashShift shift = new CashShift();
        shift.setId(id);
        shift.setCashierId(CASHIER_ID);
        shift.setShiftType(ShiftType.CASH_DRAWER);
        shift.setStatus("OPEN");
        shift.setOpeningTime(LocalDateTime.of(2026, 11, 16, 8, 0));
        shift.setDrawerSession(session);
        return shift;
    }

    /** The cashier's own shift is the only one left on the drawer. */
    private CashShift wireDeactivation(Long shiftId, CashDrawerSession session, List<CashShift> openOnDrawer) {
        CashShift shift = openShift(shiftId, session);
        when(userRepository.findById(CASHIER_ID)).thenReturn(Optional.of(cashier()));
        when(cashShiftRepository.findByCashierIdAndStatus(CASHIER_ID, "OPEN")).thenReturn(Optional.of(shift));
        when(cashShiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        if (session != null) {
            when(cashShiftRepository.findByDrawerSessionIdAndStatus(session.getId(), "OPEN")).thenReturn(openOnDrawer);
        }
        return shift;
    }

    @Test
    void forceClosingTheOnlyShiftOnAnOpenDrawerIsRejected() {
        CashDrawerSession session = openSession();
        CashShift shift = wireDeactivation(SHIFT_ID, session, List.of());

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.updateUser(CASHIER_ID, deactivateRequest()));

        assertEquals("Close the drawer first, then retry", failure.getMessage());
        verify(cashShiftService, never()).closeShift(anyLong(), any(CloseShiftRequest.class), anyLong(), anyBoolean());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void aShiftSharingTheDrawerWithAnotherOpenShiftClosesOnlyThatShiftAsTheManager() {
        CashDrawerSession session = openSession();
        CashShift shift = openShift(SHIFT_ID, session);
        CashShift colleague = openShift(41L, session);
        colleague.setCashierId(6L);
        wireDeactivation(SHIFT_ID, session, List.of(shift, colleague));

        service.updateUser(CASHIER_ID, deactivateRequest());

        ArgumentCaptor<CloseShiftRequest> requestCaptor = ArgumentCaptor.forClass(CloseShiftRequest.class);
        ArgumentCaptor<Long> actorCaptor = ArgumentCaptor.forClass(Long.class);
        verify(cashShiftService).closeShift(eq(SHIFT_ID), requestCaptor.capture(), actorCaptor.capture(), eq(true));

        assertEquals(MANAGER_ID, actorCaptor.getValue(), "the logged-in manager, not the cashier, must be the actor");
        assertEquals(Boolean.FALSE, requestCaptor.getValue().getCloseDrawer());
        assertEquals(Boolean.TRUE, requestCaptor.getValue().getForceClose());
        assertEquals("User deactivated", requestCaptor.getValue().getForceReason());
        verify(userRepository).save(any(User.class));
    }

    @Test
    void aShiftWithNoDrawerSessionIsForceClosedAsTheManager() {
        wireDeactivation(SHIFT_ID, null, List.of());

        service.updateUser(CASHIER_ID, deactivateRequest());

        verify(cashShiftService).closeShift(eq(SHIFT_ID), any(CloseShiftRequest.class), eq(MANAGER_ID), eq(true));
    }

    @Test
    void deletingAUserWithACloseableShiftRecordsTheManagerAsTheActor() {
        CashDrawerSession session = openSession();
        CashShift shift = openShift(SHIFT_ID, session);
        CashShift colleague = openShift(41L, session);
        colleague.setCashierId(6L);
        when(userRepository.findById(CASHIER_ID)).thenReturn(Optional.of(cashier()));
        when(cashShiftRepository.findByCashierIdAndStatus(CASHIER_ID, "OPEN")).thenReturn(Optional.of(shift));
        when(cashShiftRepository.findById(SHIFT_ID)).thenReturn(Optional.of(shift));
        when(cashShiftRepository.findByDrawerSessionIdAndStatus(SESSION_ID, "OPEN")).thenReturn(List.of(shift, colleague));

        service.deleteUser(CASHIER_ID, true, MANAGER_ID);

        verify(cashShiftService).closeShift(eq(SHIFT_ID), any(CloseShiftRequest.class), eq(MANAGER_ID), eq(true));
        verify(auditLogService).logAction(eq(MANAGER_ID), eq("USER_DELETE"), anyString(), eq("User"),
                eq(CASHIER_ID), anyString(), isNull());
    }

    @Test
    void deletingTheSoleShiftOnAnOpenDrawerIsRejectedBeforeTheUserIsReleased() {
        CashDrawerSession session = openSession();
        CashShift shift = openShift(SHIFT_ID, session);
        when(userRepository.findById(CASHIER_ID)).thenReturn(Optional.of(cashier()));
        when(cashShiftRepository.findByCashierIdAndStatus(CASHIER_ID, "OPEN")).thenReturn(Optional.of(shift));
        when(cashShiftRepository.findById(SHIFT_ID)).thenReturn(Optional.of(shift));
        when(cashShiftRepository.findByDrawerSessionIdAndStatus(SESSION_ID, "OPEN")).thenReturn(List.of(shift));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.deleteUser(CASHIER_ID, true, MANAGER_ID));

        assertEquals("Close the drawer first, then retry", failure.getMessage());
        verify(cashShiftService, never()).closeShift(anyLong(), any(CloseShiftRequest.class), anyLong(), anyBoolean());
        verify(userRepository, never()).save(any(User.class));
    }
}
