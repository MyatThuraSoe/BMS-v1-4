package com.bms.controller;

import com.bms.dto.response.UserResponse;
import com.bms.entity.Role;
import com.bms.entity.User;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.CashDrawerSessionRepository;
import com.bms.repository.CashShiftRepository;
import com.bms.repository.RoleRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.UserRepository;
import com.bms.service.AuditLogService;
import com.bms.service.CashShiftService;
import com.bms.service.LoginAttemptTracker;
import com.bms.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1: an admin is the only way back into a login that the counters shut.
 *
 * <p>The lockout is fifteen minutes of standing at a closed till with the right password in
 * hand, and the one person who can fix it is the person the till cannot lock. So the unlock is
 * admin-only, it works at once rather than after a restart, and it is written down - because
 * "who let you in" is the question a lock that can be lifted by hand invites.
 */
class UserControllerUnlockLoginTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long MANAGER_ID = 3L;
    private static final String MANAGER = "storemanager";

    private final LoginAttemptTracker tracker = new LoginAttemptTracker();

    private UserRepository userRepository;
    private AuditLogService auditLogService;
    private UserService userService;
    private UserController controller;
    private User admin;
    private User manager;

    @BeforeEach
    void buildTheSlice() {
        userRepository = mock(UserRepository.class);
        auditLogService = mock(AuditLogService.class);
        userService = new UserService(userRepository, mock(RoleRepository.class), mock(SaleRepository.class),
                mock(CashShiftRepository.class), mock(CashShiftService.class), mock(PasswordEncoder.class),
                auditLogService, mock(CashDrawerSessionRepository.class));
        ReflectionTestUtils.setField(userService, "attemptTracker", tracker);

        controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", userService);

        admin = account(ADMIN_ID, "admin", Role.RoleName.ROLE_ADMIN);
        manager = account(MANAGER_ID, MANAGER, Role.RoleName.ROLE_MANAGER);
        when(userRepository.findById(MANAGER_ID)).thenReturn(Optional.of(manager));
        when(userRepository.findByDeletedAtIsNullOrderByUsernameAsc()).thenReturn(List.of(admin, manager));
    }

    private User account(long id, String username, Role.RoleName role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setIsActive(true);
        Role entity = new Role();
        entity.setName(role);
        user.setRole(entity);
        return user;
    }

    /** Five wrong passwords at the login page is what shuts an account. */
    private void lockLoginOf(String username) {
        for (int attempt = 1; attempt <= 5; attempt++) {
            tracker.recordFailure(LoginAttemptTracker.loginKey(username, "192.168.1.50"),
                    LoginAttemptTracker.LOGIN);
            tracker.recordFailure(LoginAttemptTracker.loginKey(username, "192.168.1.51"),
                    LoginAttemptTracker.LOGIN);
        }
    }

    private List<UserResponse> listedUsers() {
        return controller.getAllUsers().getBody().getData();
    }

    @Test
    void theUnlockRouteIsAdminOnly() throws NoSuchMethodException {
        PreAuthorize annotation = UserController.class
                .getMethod("unlockLogin", Long.class, User.class)
                .getAnnotation(PreAuthorize.class);

        assertEquals("hasRole('ADMIN')", annotation.value());
    }

    @Test
    void theUsersListSaysWhoIsLockedAndForHowLong() {
        lockLoginOf(MANAGER);

        UserResponse locked = listedUsers().get(1);
        assertEquals(Boolean.TRUE, locked.getLoginLocked());
        assertTrue(locked.getLoginLockedMinutes() >= 1L, "the page has to say how long: "
                + locked.getLoginLockedMinutes());
        assertEquals(Boolean.FALSE, listedUsers().get(0).getLoginLocked(), "only the one who was wrong");
    }

    @Test
    void anUnlockedAccountCanSignInAtOnceAndTheUnlockIsAuditedToTheAdmin() {
        lockLoginOf(MANAGER);

        controller.unlockLogin(MANAGER_ID, admin);

        assertFalse(tracker.isLockedForUsername(MANAGER),
                "the lock is gone now, not at the next restart, and gone from every address it was shut at");
        assertEquals(Boolean.FALSE, listedUsers().get(1).getLoginLocked());

        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).logAction(eq(ADMIN_ID), eq("USER_LOGIN_UNLOCKED"), description.capture(),
                anyString(), eq(MANAGER_ID), any(), any());
        assertTrue(description.getValue().contains(MANAGER), description.getValue());
    }

    @Test
    void anUnknownAccountIsRefusedRatherThanSilentlyUnlocked() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> controller.unlockLogin(99L, admin));
        verify(auditLogService, never()).logAction(any(), anyString(), anyString(), anyString(), any(),
                any(), any());
    }
}
