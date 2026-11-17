package com.bms.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is not allowed to try a password again for a while.
 *
 * <p>Both doors into a manager's password go through here: the login endpoint and the
 * approvals a cashier can spend on a sale (a discount over the cap, an over-shipment). An
 * approval is the same secret typed by a second person standing at the till, and before this
 * existed that path had no counter at all, so a cashier could work through guesses without
 * ever touching the login form.
 *
 * <p>The state is in memory on purpose. A till is one process on one machine, and a restart
 * that clears the counters is also a restart the cashier cannot arrange.
 */
@Component
public class LoginAttemptTracker {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptTracker.class);

    /** How the login endpoint behaves. Only {@code AuthService} writes these keys. */
    public static final Policy LOGIN = new Policy(5, Duration.ofMinutes(15), Duration.ofMinutes(15));

    private final Map<String, AttemptRecord> attempts = new ConcurrentHashMap<>();

    /** Failures allowed inside {@code window} before the key is shut for {@code lockout}. */
    public record Policy(int maxFailures, Duration window, Duration lockout) {
    }

    private record AttemptRecord(int failures, Instant lastAttempt, Instant lockedUntil) {
    }

    /** The key a username is tracked under, so {@code Admin} and {@code admin} are one account. */
    public static String keyOf(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    /** The key an approving cashier is tracked under, separate from the manager they typed. */
    public static String cashierKey(Long cashierId) {
        return "cashier:" + cashierId;
    }

    /**
     * The key for one cashier guessing at one manager's password at the till.
     *
     * <p>Approval counters are namespaced so they can never be the same string as a login
     * key: a cashier working through guesses must not be able to shut a manager out of the
     * login page, and a manager who locked the login page must not be shut out of approvals.
     */
    public static String pairKey(Long cashierId, String managerUsername) {
        return "approval:" + cashierId + ":" + keyOf(managerUsername);
    }

    public boolean isLocked(String key) {
        return lockedUntil(key) != null;
    }

    /** When the key opens again, or {@code null} while it is shut. */
    public Instant lockedUntil(String key) {
        AttemptRecord record = attempts.get(key);
        if (record == null || record.lockedUntil() == null || !Instant.now().isBefore(record.lockedUntil())) {
            return null;
        }
        return record.lockedUntil();
    }

    /** Minutes left on a lock, rounded up, and at least one so a message is never "in 0 minutes". */
    public long minutesRemaining(String key) {
        Instant until = lockedUntil(key);
        if (until == null) {
            return 0L;
        }
        return Math.max(1L, Duration.between(Instant.now(), until).toMinutes() + 1);
    }

    public void recordFailure(String key, Policy policy) {
        attempts.merge(key, new AttemptRecord(1, Instant.now(), null), (existing, ignored) -> {
            if (existing.lockedUntil() != null && Instant.now().isBefore(existing.lockedUntil())) {
                return existing;
            }
            boolean withinWindow = existing.lastAttempt().isAfter(Instant.now().minus(policy.window()));
            int failures = withinWindow ? existing.failures() + 1 : 1;
            Instant lockedUntil = failures >= policy.maxFailures()
                    ? Instant.now().plus(policy.lockout()) : null;
            return new AttemptRecord(failures, Instant.now(), lockedUntil);
        });
        if (isLocked(key)) {
            log.warn("Attempts blocked for key '{}' after repeated failures", key);
        }
    }

    public void clear(String key) {
        attempts.remove(key);
    }
}
