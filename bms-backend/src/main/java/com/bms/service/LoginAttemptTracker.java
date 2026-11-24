package com.bms.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Who is not allowed to try a password again for a while.
 *
 * <p>Both doors into a manager's password go through here: the login endpoint and the
 * approvals a cashier can spend on a sale (a discount over the cap, an over-shipment). An
 * approval is the same secret typed by a second person standing at the till, and before this
 * existed that path had no counter at all, so a cashier could work through guesses without
 * ever touching the login form.
 *
 * <p>A sign-in is counted against the pair of username and address, not the username alone.
 * Five wrong guesses at {@code admin} from one computer used to lock {@code admin} from every
 * computer, and the button that opens the lock belongs to {@code admin} - so one curious
 * person on the shop network could shut the only admin out and none of the fixes were
 * available to them. The address is counted as well, so one machine cannot walk through the
 * whole username list either.
 *
 * <p>The state is in memory on purpose. A till is one process on one machine, and a restart
 * that clears the counters is also a restart the cashier cannot arrange. Because that process
 * may run for months, a record is dropped as soon as it can no longer change an answer, and
 * the map has a ceiling.
 */
@Component
public class LoginAttemptTracker {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptTracker.class);

    /** How the login endpoint behaves. Only {@code AuthService} writes these keys. */
    public static final Policy LOGIN = new Policy(5, Duration.ofMinutes(15), Duration.ofMinutes(15));

    /**
     * How often one address may be wrong across every username before the address itself is
     * shut. The number a shop actually wants is {@code auth.ipMaxFailures}; this is the
     * fallback that ships.
     */
    public static final Policy ADDRESS = new Policy(20, Duration.ofMinutes(15), Duration.ofMinutes(15));

    /**
     * Ceiling on tracked keys. The window makes the real working set small - one entry per
     * address and per username that was tried recently - so reaching this means somebody is
     * generating names rather than mistyping one.
     */
    public static final int MAX_KEYS = 10_000;

    private static final String LOGIN_PREFIX = "login:";
    private static final String ADDRESS_PREFIX = "ip:";

    /** Every five minutes, which is short beside a fifteen-minute window and long beside a CPU. */
    private static final long CLEANUP_INTERVAL_MS = 5 * 60 * 1000L;

    private final Map<String, AttemptRecord> attempts = new ConcurrentHashMap<>();

    /** Time, replaceable so a test can wait a lock out without actually waiting. */
    private Supplier<Instant> clock = Instant::now;

    /** Failures allowed inside {@code window} before the key is shut for {@code lockout}. */
    public record Policy(int maxFailures, Duration window, Duration lockout) {
    }

    /** The window is carried per record so cleanup can judge it without being told the policy. */
    private record AttemptRecord(int failures, Instant lastAttempt, Duration window, Instant lockedUntil) {
    }

    /** The key a username is tracked under, so {@code Admin} and {@code admin} are one account. */
    public static String keyOf(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The key one username is locked at one address.
     *
     * <p>An unknown address still gets a key of its own rather than the bare username:
     * mixing the two would let a caller with no address share a bucket with the whole
     * network, which is the fault this fixed.
     */
    public static String loginKey(String username, String clientAddress) {
        return LOGIN_PREFIX + keyOf(username) + ":" + (clientAddress == null ? "unknown" : clientAddress.trim());
    }

    /**
     * The key one address is locked under, or {@code null} when no address is known. A
     * shared "nobody" bucket would let one failure from a background call shut every
     * machine in the shop.
     */
    public static String addressKey(String clientAddress) {
        if (clientAddress == null || clientAddress.isBlank()) {
            return null;
        }
        return ADDRESS_PREFIX + clientAddress.trim();
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
        if (key == null) {
            return null;
        }
        AttemptRecord record = attempts.get(key);
        if (record == null || record.lockedUntil() == null || !now().isBefore(record.lockedUntil())) {
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
        return Math.max(1L, Duration.between(now(), until).toMinutes() + 1);
    }

    /**
     * True when this username is shut out from at least one address. Whoever is asking is an
     * admin with the unlock button, not the guesser, so the answer errs towards showing a
     * lock they would have to clear even when their own machine was never involved.
     */
    public boolean isLockedForUsername(String username) {
        return keysOf(username).anyMatch(this::isLocked);
    }

    /** Minutes left on the longest lock this username has anywhere, zero when it is free. */
    public long minutesRemainingForUsername(String username) {
        return keysOf(username).mapToLong(this::minutesRemaining).max().orElse(0L);
    }

    /**
     * Frees one username from every address it was locked at. This is the admin's remedy for
     * an account that cannot sign in, and it has to cover the addresses the admin is not
     * sitting at.
     */
    public void clearUsername(String username) {
        String prefix = loginPrefix(username);
        attempts.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public void clear(String key) {
        if (key != null) {
            attempts.remove(key);
        }
    }

    /**
     * Counts one failure against {@code key}.
     *
     * @return true when the failure was recorded; false when the key was refused because the
     *         map is full, or when there was no key to count against
     */
    public boolean recordFailure(String key, Policy policy) {
        if (key == null) {
            return false;
        }
        if (!attempts.containsKey(key) && attempts.size() >= MAX_KEYS) {
            removeExpiredRecords();
            if (!attempts.containsKey(key) && attempts.size() >= MAX_KEYS) {
                if (key.startsWith(ADDRESS_PREFIX)) {
                    // The address count is what protects the shop, so it always finds room.
                    makeRoomForAddressKey();
                }
                if (!attempts.containsKey(key) && attempts.size() >= MAX_KEYS) {
                    log.warn("Login tracking is full at " + MAX_KEYS + " keys, so the failures of '"
                            + key + "' were not counted. The address they came from is counted, "
                            + "and expired keys are removed every few minutes.");
                    return false;
                }
            }
        }

        Instant now = now();
        attempts.merge(key, new AttemptRecord(1, now, policy.window(), null), (existing, ignored) -> {
            if (existing.lockedUntil() != null && now.isBefore(existing.lockedUntil())) {
                return existing;
            }
            boolean withinWindow = existing.lastAttempt().isAfter(now.minus(policy.window()));
            int failures = withinWindow ? existing.failures() + 1 : 1;
            Instant lockedUntil = failures >= policy.maxFailures() ? now.plus(policy.lockout()) : null;
            return new AttemptRecord(failures, now, policy.window(), lockedUntil);
        });
        if (isLocked(key)) {
            log.warn("Attempts blocked for key '{}' after repeated failures", key);
        }
        return true;
    }

    /** How many keys are held, which is what the ceiling and the cleanup are about. */
    public int trackedKeys() {
        return attempts.size();
    }

    /**
     * Drops every record that can no longer decide anything: its window has closed and no
     * lock is running. Without this a till that is never restarted remembers every username
     * ever typed at it, including the ones a script invented.
     */
    @Scheduled(fixedDelay = CLEANUP_INTERVAL_MS)
    public void removeExpiredRecords() {
        int before = attempts.size();
        attempts.values().removeIf(this::expired);
        int removed = before - attempts.size();
        if (removed > 0) {
            log.debug("Removed " + removed + " login-attempt keys that had expired; "
                    + attempts.size() + " still tracked.");
        }
    }

    /** Replaces the clock. Package-private, for tests that move time forward. */
    void setClock(Supplier<Instant> clock) {
        this.clock = clock;
    }

    private Instant now() {
        return clock.get();
    }

    private boolean expired(AttemptRecord record) {
        Instant now = now();
        boolean noLockRunning = record.lockedUntil() == null || !now.isBefore(record.lockedUntil());
        return noLockRunning && record.lastAttempt().plus(record.window()).isBefore(now);
    }

    /** The per-address keys of one username, whichever address they came from. */
    private java.util.stream.Stream<String> keysOf(String username) {
        String prefix = loginPrefix(username);
        return attempts.keySet().stream().filter(key -> key.startsWith(prefix));
    }

    /**
     * Trailing colon matters: without it the prefix of {@code admin} would also match
     * {@code admin2}.
     */
    private static String loginPrefix(String username) {
        return LOGIN_PREFIX + keyOf(username) + ":";
    }

    /** Evicts the oldest username count so an address count can be kept. */
    private void makeRoomForAddressKey() {
        attempts.entrySet().stream()
                .filter(entry -> !entry.getKey().startsWith(ADDRESS_PREFIX))
                .filter(entry -> entry.getValue().lockedUntil() == null
                        || !now().isBefore(entry.getValue().lockedUntil()))
                .min(Comparator.comparing(entry -> entry.getValue().lastAttempt()))
                .map(Map.Entry::getKey)
                .ifPresent(attempts::remove);
    }
}
