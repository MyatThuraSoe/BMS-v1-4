package com.bms.service;

import com.bms.entity.Role;
import com.bms.entity.SystemSetting;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * M2: a manager password typed at the till is a login attempt.
 *
 * <p>The sale and receiving screens let a cashier name a manager and type that manager's
 * password to clear a limit. Before this shared path each screen checked the password on its
 * own with no counter behind it, so the till was an unlimited guessing machine that left no
 * trace. These tests pin the four things that replaced that: the guesses stop, the guessing
 * is written down, the cashier who asked is counted too, and a refusal never explains itself.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ManagerApprovalLockoutTest {

    private static final Long CASHIER = 9L;
    private static final String HASH = "stored-hash";

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private SystemSettingRepository systemSettingRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private PlatformTransactionManager transactionManager;

    private LoginAttemptTracker tracker;
    private ManagerApprovalService approvals;
    private final List<String> auditTexts = new ArrayList<>();
    private final List<String> auditActions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tracker = new LoginAttemptTracker();
        approvals = new ManagerApprovalService(userRepository, passwordEncoder, systemSettingRepository,
                auditLogService, tracker, transactionManager);
        lenient().when(systemSettingRepository.findBySettingKey(anyString())).thenReturn(Optional.empty());
        lenient().when(userRepository.findActiveByUsernameIgnoreCase("manager"))
                .thenReturn(Optional.of(user(3L, "manager", Role.RoleName.ROLE_MANAGER)));
        lenient().when(passwordEncoder.matches("hunter2", HASH)).thenReturn(true);
        lenient().doAnswer(invocation -> {
            auditActions.add(invocation.getArgument(1));
            auditTexts.add(invocation.getArgument(2));
            return null;
        }).when(auditLogService).logAction(org.mockito.ArgumentMatchers.any(), anyString(), anyString(),
                anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private User user(long id, String username, Role.RoleName role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword(HASH);
        if (role != null) {
            Role entity = new Role();
            entity.setName(role);
            user.setRole(entity);
        }
        return user;
    }

    private void setting(String key, String value) {
        SystemSetting setting = new SystemSetting();
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        when(systemSettingRepository.findBySettingKey(key)).thenReturn(Optional.of(setting));
    }

    private String approve(String username, String password) {
        try {
            approvals.verify(username, password, ManagerApprovalService.Purpose.SALE_DISCOUNT, CASHIER);
            return null;
        } catch (BusinessException ex) {
            return ex.getMessage();
        }
    }

    @Test
    void nothingEnteredIsNotAnAttempt() {
        assertFalse(approvals.verify(null, null, ManagerApprovalService.Purpose.SALE_DISCOUNT, CASHIER));
        assertFalse(approvals.verify("manager", "", ManagerApprovalService.Purpose.SALE_DISCOUNT, CASHIER));

        verifyNoInteractions(auditLogService);
    }

    @Test
    void theSixthGuessIsRefusedEvenWhenItIsRight() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertEquals("Invalid manager credentials", approve("manager", "guess-" + attempt),
                    "attempt " + attempt);
        }

        assertTrue(tracker.isLocked("manager"), "the manager account must be locked");
        assertTrue(tracker.isLocked(LoginAttemptTracker.cashierKey(CASHIER)),
                "the cashier who typed it must be locked out of asking as well");
        // Login uses the same tracker, so a manager the till locked cannot sign in either.
        BusinessException sixth = assertThrows(BusinessException.class,
                () -> approvals.verify("manager", "hunter2",
                        ManagerApprovalService.Purpose.SALE_DISCOUNT, CASHIER));
        assertTrue(sixth.getMessage().contains("Invalid manager credentials")
                        || sixth.getMessage().contains("Too many failed manager approvals"),
                "the right password must still not get through: " + sixth.getMessage());
    }

    @Test
    void aGoodApprovalAfterFailuresStartsTheCountingAgain() {
        approve("manager", "wrong-1");
        approve("manager", "wrong-2");

        assertTrue(approvals.verify("manager", "hunter2", ManagerApprovalService.Purpose.SALE_DISCOUNT, CASHIER));

        // Two are already spent, so it takes three more to lock, not one.
        approve("manager", "wrong-3");
        approve("manager", "wrong-4");
        approve("manager", "wrong-5");
        assertTrue(approvals.verify("manager", "hunter2", ManagerApprovalService.Purpose.SALE_DISCOUNT, CASHIER),
                "the counter must have been cleared by the approval, not carried by the failures");
    }

    @Test
    void theRecordOfARefusedApprovalOutlivesTheSaleItWasAskedFor() {
        // A refusal throws, which rolls the sale back. An audit row written inside that
        // transaction would be rolled back with it, so the guess would leave no trace.
        TransactionSynchronizationManager.initSynchronization();
        String message;
        try {
            message = approve("manager", "nope");
            assertEquals(List.of(), auditActions, "nothing is written while the sale is still open");
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertTrue(message != null && message.contains(ManagerApprovalService.INVALID), message);
        assertEquals(List.of("MANAGER_APPROVAL_FAILED"), auditActions);
        assertTrue(auditTexts.get(0).contains("refused"), auditTexts.get(0));
    }

    @Test
    void everyResultIsAuditedWithoutThePassword() {
        approve("manager", "hunter2");
        approve("Manager", "guess");

        assertEquals(List.of("MANAGER_APPROVAL_OK", "MANAGER_APPROVAL_FAILED"), auditActions);
        String failure = auditTexts.get(1);
        assertTrue(failure.contains("sale_discount"), failure);
        assertTrue(failure.contains("cashier " + CASHIER), failure);
        assertTrue(failure.contains("manager"), failure);
        assertTrue(!failure.contains("guess"), "the password must never reach the audit text: " + failure);
        assertTrue(!failure.contains("hunter2"), "the password must never reach the audit text: " + failure);
    }

    @Test
    void anUnknownUserAWrongPasswordAndACashierAllSayTheSameThing() {
        when(userRepository.findActiveByUsernameIgnoreCase("ghost")).thenReturn(Optional.empty());
        when(userRepository.findActiveByUsernameIgnoreCase("clerk"))
                .thenReturn(Optional.of(user(4L, "clerk", Role.RoleName.ROLE_CASHIER)));
        lenient().when(passwordEncoder.matches("hunter2", HASH)).thenReturn(true);

        String unknown = approve("ghost", "hunter2");
        String wrongPassword = approve("manager", "nope");
        String notAManager = approve("clerk", "hunter2");

        assertEquals("Invalid manager credentials", unknown);
        assertEquals(unknown, wrongPassword);
        assertEquals(unknown, notAManager);
    }

    @Test
    void aCashierOutrunningTheApprovalBudgetIsShutOutOfApprovals() {
        setting("auth.approval.maxFailures", "2");
        setting("auth.approval.lockMinutes", "10");

        approve("manager", "wrong-1");
        approve("manager", "wrong-2");

        // A different manager now: the till is what got blocked, not that account.
        when(userRepository.findActiveByUsernameIgnoreCase("boss"))
                .thenReturn(Optional.of(user(5L, "boss", Role.RoleName.ROLE_MANAGER)));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> approvals.verify("boss", "hunter2", ManagerApprovalService.Purpose.SALE_DISCOUNT, CASHIER));
        assertTrue(ex.getMessage().contains("Too many failed manager approvals"), ex.getMessage());
        // and the right password for the first manager is refused too, for the same reason
        assertThrows(BusinessException.class,
                () -> approvals.verify("manager", "hunter2", ManagerApprovalService.Purpose.SALE_DISCOUNT, CASHIER));
    }

    @Test
    void anotherCashierKeepsTheirOwnApprovalBudget() {
        setting("auth.approval.maxFailures", "1");

        assertThrows(BusinessException.class,
                () -> approvals.verify("manager", "wrong", ManagerApprovalService.Purpose.SALE_DISCOUNT, CASHIER));

        assertTrue(approvals.verify("manager", "hunter2", ManagerApprovalService.Purpose.SALE_DISCOUNT, 11L));
    }

    @Test
    void anUnreadableApprovalSettingFallsBackToTheShippedNumbers() {
        setting("auth.approval.maxFailures", "plenty");
        setting("auth.approval.lockMinutes", "-3");

        for (int attempt = 1; attempt <= 4; attempt++) {
            approve("manager", "guess-" + attempt);
        }
        // Still open: the default of five failures survived the bad rows.
        assertTrue(approvals.verify("manager", "hunter2", ManagerApprovalService.Purpose.SALE_DISCOUNT, CASHIER));
    }

    @Test
    void anOverShipmentApprovalCountsAgainstTheSameManagerAndCashier() {
        // Two purposes share one budget: guessing at a manager is guessing, whatever gate
        // the cashier is standing in front of.
        approve("manager", "wrong-1");
        assertThrows(BusinessException.class, () -> approvals.verify("manager", "wrong-2",
                ManagerApprovalService.Purpose.OVER_SHIPMENT, CASHIER));
        assertFalse(tracker.isLocked("manager"));

        approve("manager", "wrong-3");
        approve("manager", "wrong-4");
        approve("manager", "wrong-5");
        assertTrue(tracker.isLocked("manager"), "the fifth failure locks regardless of purpose");
    }

    @Test
    void theAuditedManagerNameIsTheAccountNotTheTyping() {
        approve("  MANAGer ", "guess");

        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(auditLogService).logAction(org.mockito.ArgumentMatchers.eq(CASHIER),
                org.mockito.ArgumentMatchers.eq("MANAGER_APPROVAL_FAILED"), description.capture(),
                anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        assertTrue(description.getValue().contains("'manager'"), description.getValue());
    }
}
