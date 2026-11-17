package com.bms.service;

import com.bms.entity.Role;
import com.bms.entity.SystemSetting;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.repository.SystemSettingRepository;
import com.bms.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Locale;

/**
 * The one place a manager's password is checked for a thing happening at the till.
 *
 * <p>A discount over the cashier's cap, a sale below cost, a delivery of more than was
 * ordered: each of these asks a second person to type their password so the first one is not
 * unlimited. That is a login attempt in all but name, and when each caller checked the
 * password itself it skipped the counter the login endpoint keeps - so standing at the till
 * with a sale screen open was the cheapest way to work through guesses about a manager's
 * password. Counting here, and writing down what happened, closes that.
 *
 * <p>The counting is kept apart from the login page's counters, not shared with them. Whoever
 * is guessing stands at the till, so it is their own approvals that get shut; a manager cannot
 * be locked out of signing in by somebody else typing their password wrong five times.
 *
 * <p>Nothing about the outcome distinguishes an account that does not exist from a password
 * that is wrong from a cashier who is not a manager, because the difference between those is
 * exactly what a person guessing wants to know.
 */
@Service
public class ManagerApprovalService {

    public enum Purpose {
        SALE_DISCOUNT, BELOW_COST, OVER_SHIPMENT
    }

    /** The single answer to every rejection, so nothing is revealed about the account. */
    static final String INVALID = "Invalid manager credentials";

    static final String MAX_FAILURES_KEY = "auth.approval.maxFailures";
    static final String LOCK_MINUTES_KEY = "auth.approval.lockMinutes";
    static final int DEFAULT_MAX_FAILURES = 5;
    static final int DEFAULT_LOCK_MINUTES = 10;

    private static final Logger log = LoggerFactory.getLogger(ManagerApprovalService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SystemSettingRepository systemSettingRepository;
    private final AuditLogService auditLogService;
    private final LoginAttemptTracker attemptTracker;
    private final TransactionOperations auditTransaction;

    public ManagerApprovalService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                  SystemSettingRepository systemSettingRepository,
                                  AuditLogService auditLogService, LoginAttemptTracker attemptTracker,
                                  PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.systemSettingRepository = systemSettingRepository;
        this.auditLogService = auditLogService;
        this.attemptTracker = attemptTracker;
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.auditTransaction = requiresNew;
    }

    /**
     * Whether these credentials approve {@code purpose}. Silent {@code false} when the cashier
     * typed nothing at all, which is how a sale with no override arrives; once a password has
     * been offered, a wrong one is an error rather than a quiet no.
     *
     * @param cashierId who is standing at the till asking, for the counter and the audit trail
     */
    public boolean verify(String username, String password, Purpose purpose, Long cashierId) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return false;
        }
        String managerKey = LoginAttemptTracker.keyOf(username);
        String cashierKey = LoginAttemptTracker.cashierKey(cashierId);
        String pairKey = LoginAttemptTracker.pairKey(cashierId, managerKey);

        if (attemptTracker.isLocked(cashierKey)) {
            long minutes = attemptTracker.minutesRemaining(cashierKey);
            audit(cashierId, managerKey, purpose, false,
                    "the cashier has used up their failed approvals for " + minutes + " minute(s)");
            throw new BusinessException("Too many failed manager approvals from this cashier."
                    + " Try again in " + minutes + " minute(s).");
        }
        if (attemptTracker.isLocked(pairKey)) {
            audit(cashierId, managerKey, purpose, false,
                    "this cashier has been refused against that manager too often");
            throw new BusinessException(INVALID);
        }

        User manager = userRepository.findActiveByUsernameIgnoreCase(username.trim()).orElse(null);
        Role.RoleName role = manager != null && manager.getRole() != null ? manager.getRole().getName() : null;
        boolean privileged = role == Role.RoleName.ROLE_ADMIN || role == Role.RoleName.ROLE_MANAGER;
        if (manager == null || !privileged || !passwordEncoder.matches(password, manager.getPassword())) {
            reject(cashierKey, pairKey, managerKey, cashierId, purpose);
        }

        attemptTracker.clear(cashierKey);
        attemptTracker.clear(pairKey);
        audit(cashierId, managerKey, purpose, true, null);
        return true;
    }

    /**
     * Counting is what stops the guessing, and it is counted where the guessing happens: on
     * the cashier with the sale screen open, and on that cashier against that manager. The
     * manager's own login counter is left alone, because otherwise five wrong passwords typed
     * by a till would keep a manager out of the system for a quarter of an hour, and a cashier
     * whose own lock has expired could do it again as often as they like.
     */
    private void reject(String cashierKey, String pairKey, String managerKey, Long cashierId,
                        Purpose purpose) {
        LoginAttemptTracker.Policy policy = approvalPolicy();
        attemptTracker.recordFailure(cashierKey, policy);
        attemptTracker.recordFailure(pairKey, policy);
        audit(cashierId, managerKey, purpose, false, "the credentials were not accepted");
        throw new BusinessException(INVALID);
    }

    /**
     * How often one cashier may be wrong about a manager's password before their own approvals
     * are shut, and for how long. A wrong guess costs the manager nothing here; the cashier is
     * the one with the sale screen open and the time to spare.
     */
    private LoginAttemptTracker.Policy approvalPolicy() {
        int maxFailures = positiveInt(MAX_FAILURES_KEY, DEFAULT_MAX_FAILURES);
        int lockMinutes = positiveInt(LOCK_MINUTES_KEY, DEFAULT_LOCK_MINUTES);
        return new LoginAttemptTracker.Policy(maxFailures, Duration.ofMinutes(lockMinutes),
                Duration.ofMinutes(lockMinutes));
    }

    private int positiveInt(String key, int fallback) {
        String raw = systemSettingRepository.findBySettingKey(key)
                .map(SystemSetting::getSettingValue).orElse(null);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = new BigDecimal(raw.trim()).intValueExact();
            if (value <= 0) {
                throw new ArithmeticException("not positive");
            }
            return value;
        } catch (ArithmeticException | NumberFormatException ex) {
            log.warn("Setting {} holds \"{}\" which is not a positive whole number; using {}",
                    key, raw, fallback);
            return fallback;
        }
    }

    private void audit(Long cashierId, String managerUsername, Purpose purpose, boolean approved,
                       String reason) {
        String description = "Manager approval " + (approved ? "granted" : "refused")
                + " for " + purpose.name().toLowerCase(Locale.ROOT)
                + " by cashier " + cashierId + " with manager '" + managerUsername + "'"
                + (reason == null ? "" : ": " + reason);
        String action = approved ? "MANAGER_APPROVAL_OK" : "MANAGER_APPROVAL_FAILED";
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // A refused approval rolls the sale back, and the sale's transaction owns the
            // row written inside it, so writing here would erase the very evidence that
            // somebody was guessing. The row is written once that transaction is finished,
            // whichever way it finished.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    writeAuditRow(action, description, cashierId);
                }
            });
            return;
        }
        writeAuditRow(action, description, cashierId);
    }

    private void writeAuditRow(String action, String description, Long cashierId) {
        try {
            // REQUIRES_NEW, not a plain call: when this runs from afterCompletion the finished
            // transaction is still bound to the thread, and a save that joined it would be
            // dropped with it - the guess would leave no trace, which is the whole point.
            // A new transaction is safe here because the sale's own transaction has already
            // committed or rolled back, so its SQLite write lock has been released.
            auditTransaction.executeWithoutResult(status ->
                    auditLogService.logAction(cashierId, action, description, "User", cashierId, null, null));
        } catch (Exception ex) {
            // The sale must not hinge on the audit row, but a silent approval trail would be
            // worse than a missing one, so it also reaches the log.
            log.error("Could not audit {} for cashier {}: {}", action, cashierId, ex.getMessage());
        }
    }
}
