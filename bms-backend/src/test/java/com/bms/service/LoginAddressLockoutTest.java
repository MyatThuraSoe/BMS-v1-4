package com.bms.service;

import com.bms.config.JwtUtil;
import com.bms.dto.request.LoginRequest;
import com.bms.entity.Role;
import com.bms.entity.SystemSetting;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.repository.SystemSettingRepository;
import com.bms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Q2: whoever is guessing gets shut out, and nobody else is.
 *
 * <p>Counting failures by username alone meant five wrong guesses at
 * {@code admin} from any computer on the network locked {@code admin} everywhere,
 * and the only unlock was the admin-only button - which is the account that was
 * just locked. The count therefore belongs to the pair (username, address), with a
 * second count per address so one machine cannot work through the whole username
 * list either.
 *
 * <p>The other half is memory. These keys live in a map inside a process that a
 * shop may run for months without restarting, so a record that can no longer change
 * an answer has to go, and the map has a ceiling.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoginAddressLockoutTest {

    private static final String ADMIN = "admin";
    private static final String ADDRESS_A = "192.168.1.10";
    private static final String ADDRESS_B = "192.168.1.11";
    private static final String HASH = "stored-hash";
    private static final String RIGHT = "hunter2";

    @Mock private UserRepository userRepository;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private SystemSettingRepository systemSettingRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private JwtUtil jwtUtil;

    private LoginAttemptTracker tracker;
    private LoginAttemptPolicy addressPolicy;
    private AuthService auth;

    @BeforeEach
    void setUp() {
        tracker = new LoginAttemptTracker();
        addressPolicy = new LoginAttemptPolicy(systemSettingRepository);
        auth = new AuthService();
        ReflectionTestUtils.setField(auth, "authenticationManager", authenticationManager);
        ReflectionTestUtils.setField(auth, "userRepository", userRepository);
        ReflectionTestUtils.setField(auth, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(auth, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(auth, "attemptTracker", tracker);
        ReflectionTestUtils.setField(auth, "loginPolicy", addressPolicy);

        User admin = new User();
        admin.setId(1L);
        admin.setUsername(ADMIN);
        admin.setPassword(HASH);
        admin.setIsActive(true);
        admin.setPreferredLanguage("en");
        Role role = new Role();
        role.setName(Role.RoleName.ROLE_ADMIN);
        admin.setRole(role);

        lenient().when(userRepository.findByUsernameIgnoreCase(ADMIN)).thenReturn(Optional.of(admin));
        lenient().when(userRepository.findByUsernameIgnoreCase(anyString()))
                .thenAnswer(invocation -> ADMIN.equalsIgnoreCase(invocation.getArgument(0))
                        ? Optional.of(admin) : Optional.empty());
        lenient().when(jwtUtil.generateToken(any())).thenReturn("jwt");
        lenient().when(systemSettingRepository.findBySettingKey(anyString())).thenReturn(Optional.empty());

        // The password is what decides the answer, so the same stub serves both the
        // wrong guesses and the good one without restubbing between attempts.
        Authentication principal = mock(Authentication.class);
        lenient().when(principal.getPrincipal()).thenReturn(admin);
        lenient().when(authenticationManager.authenticate(any())).thenAnswer(invocation -> {
            UsernamePasswordAuthenticationToken token = invocation.getArgument(0);
            if (!RIGHT.equals(String.valueOf(token.getCredentials()))) {
                throw new BadCredentialsException("Bad credentials");
            }
            return principal;
        });
    }

    /** The message the login page would show, or null when the sign-in succeeded. */
    private String signIn(String username, String address) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword("wrong-password");
        try {
            auth.authenticateUser(request, address);
            return null;
        } catch (BusinessException ex) {
            return ex.getMessage();
        }
    }

    private String signInWithRightPassword(String address) {
        LoginRequest request = new LoginRequest();
        request.setUsername(ADMIN);
        request.setPassword(RIGHT);
        try {
            auth.authenticateUser(request, address);
            return null;
        } catch (BusinessException ex) {
            return ex.getMessage();
        }
    }

    private void setting(String key, String value) {
        SystemSetting setting = new SystemSetting();
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        when(systemSettingRepository.findBySettingKey(key)).thenReturn(Optional.of(setting));
    }

    @Test
    void fiveWrongPasswordsFromOneAddressLockThatAddressAndNoOther() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertEquals("auth.login.failed", signIn(ADMIN, ADDRESS_A), "attempt " + attempt);
        }

        assertTrue(tracker.isLocked(LoginAttemptTracker.loginKey(ADMIN, ADDRESS_A)),
                "the address that was wrong five times must be the one that is shut");
        assertFalse(tracker.isLocked(LoginAttemptTracker.loginKey(ADMIN, ADDRESS_B)),
                "and the lock must belong to that pair alone");

        assertNull(signInWithRightPassword(ADDRESS_B),
                "a different computer has to be able to sign in as admin while the first is locked");
    }

    @Test
    void aLockedAddressGetsTheSameMessageAsAWrongPassword() {
        // Three is enough here: what is being proved is the answer an address gets once
        // it is shut, not how many tries it takes. The guesses are for accounts that do
        // not exist, so only the address key can be what locked.
        setting("auth.ipMaxFailures", "3");
        for (int attempt = 1; attempt <= 3; attempt++) {
            signIn("nobody-" + attempt, ADDRESS_A);
        }
        assertTrue(tracker.isLocked(LoginAttemptTracker.addressKey(ADDRESS_A)), "test setup needs the address shut");

        assertEquals("auth.login.failed", signInWithRightPassword(ADDRESS_A),
                "a shut address is refused like any wrong password, before the account is even asked");
        assertNull(signInWithRightPassword(ADDRESS_B), "the same account still signs in from elsewhere");
    }

    @Test
    void guessingUnknownUsernamesBuysNoKeysAndShutsTheAddress() {
        setting("auth.ipMaxFailures", "20");

        for (int attempt = 1; attempt <= 100; attempt++) {
            assertEquals("auth.login.failed", signIn("nobody-" + attempt, ADDRESS_A), "attempt " + attempt);
        }

        assertTrue(tracker.isLocked(LoginAttemptTracker.addressKey(ADDRESS_A)),
                "twenty failures across any usernames must shut the address");
        assertTrue(tracker.trackedKeys() <= 1,
                "a username that does not exist must not buy a key of its own: " + tracker.trackedKeys());

        // The whole shop is untouched: the address is what got shut.
        assertNull(signInWithRightPassword(ADDRESS_B), "another address keeps working");
    }

    @Test
    void aFullTrackerStopsCountingNewKeysButStillCountsTheAddress() {
        for (int i = 0; i < LoginAttemptTracker.MAX_KEYS + 5; i++) {
            tracker.recordFailure(LoginAttemptTracker.loginKey("user-" + i, ADDRESS_A),
                    LoginAttemptTracker.LOGIN);
        }

        assertEquals(LoginAttemptTracker.MAX_KEYS, tracker.trackedKeys(),
                "the map never grows past the ceiling");
        assertFalse(tracker.recordFailure(LoginAttemptTracker.loginKey("one-more", ADDRESS_B),
                LoginAttemptTracker.LOGIN), "a key that cannot fit is refused rather than evicting a lock");

        assertTrue(tracker.recordFailure(LoginAttemptTracker.addressKey(ADDRESS_B),
                LoginAttemptTracker.ADDRESS), "the address of an attacker is still countable");
    }

    @Test
    void recordsThatCannotChangeAnAnswerAnyMoreAreRemoved() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
        tracker.setClock(now::get);

        tracker.recordFailure(LoginAttemptTracker.loginKey(ADMIN, ADDRESS_A), LoginAttemptTracker.LOGIN);
        assertEquals(1, tracker.trackedKeys(), "the failure is remembered while its window runs");

        now.set(now.get().plus(LoginAttemptTracker.LOGIN.window()).plus(Duration.ofMinutes(1)));
        tracker.removeExpiredRecords();
        assertEquals(0, tracker.trackedKeys(),
                "after the window, and with no lock running, the record decides nothing");
    }

    @Test
    void aRunningLockSurvivesTheCleanupAndIsRemovedOnceItExpires() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
        tracker.setClock(now::get);

        String key = LoginAttemptTracker.loginKey(ADMIN, ADDRESS_A);
        for (int attempt = 1; attempt <= 5; attempt++) {
            tracker.recordFailure(key, LoginAttemptTracker.LOGIN);
        }
        assertTrue(tracker.isLocked(key), "test setup needs the lock to be running");

        now.set(now.get().plus(Duration.ofMinutes(5)));
        tracker.removeExpiredRecords();
        assertTrue(tracker.isLocked(key), "a lock with minutes left is not expired just because its window passed");

        now.set(now.get().plus(LoginAttemptTracker.LOGIN.lockout()));
        tracker.removeExpiredRecords();
        assertFalse(tracker.isLocked(key), "and once the lock is over it has to be gone as well");
        assertEquals(0, tracker.trackedKeys(), "the opened lock is not remembered either");
    }

    @Test
    void aGoodLoginStartsThatAddressCountingAgain() {
        signIn(ADMIN, ADDRESS_A);
        signIn(ADMIN, ADDRESS_A);
        signIn(ADMIN, ADDRESS_A);
        assertEquals(null, signInWithRightPassword(ADDRESS_A), "test setup needs the sign-in to work");

        // The three failures are forgiven, so the lock arrives on the fifth after this,
        // not on the second.
        signIn(ADMIN, ADDRESS_A);
        signIn(ADMIN, ADDRESS_A);
        signIn(ADMIN, ADDRESS_A);
        signIn(ADMIN, ADDRESS_A);
        assertFalse(tracker.isLocked(LoginAttemptTracker.loginKey(ADMIN, ADDRESS_A)),
                "four failures after a good login are not five");
        signIn(ADMIN, ADDRESS_A);
        assertTrue(tracker.isLocked(LoginAttemptTracker.loginKey(ADMIN, ADDRESS_A)),
                "and the fifth is");
    }

    @Test
    void theUnlockButtonClearsEveryAddressOfThatAccount() {
        signIn(ADMIN, ADDRESS_A);
        signIn(ADMIN, ADDRESS_A);
        for (int attempt = 1; attempt <= 5; attempt++) {
            signIn(ADMIN, ADDRESS_A);
        }
        for (int attempt = 1; attempt <= 5; attempt++) {
            signIn(ADMIN, "192.168.1.99");
        }
        assertTrue(tracker.isLockedForUsername(ADMIN), "test setup needs the account locked somewhere");

        tracker.clearUsername(ADMIN);

        assertFalse(tracker.isLockedForUsername(ADMIN), "an admin unlocking an account must free it everywhere");
        assertNull(signInWithRightPassword(ADDRESS_A));
    }

    @Test
    void theForwardedHeaderIsBelievedOnlyWhenThisInstallSitsBehindAProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");

        assertEquals("10.0.0.9", addressPolicy.clientAddress(request),
                "by default the header is whatever the caller says it is, so it cannot be a key");

        setting("auth.trustForwardedFor", "true");
        assertEquals("203.0.113.7", addressPolicy.clientAddress(request),
                "behind a proxy the left-most entry is the client and the rest belong to proxies");

        request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        assertEquals("10.0.0.9", addressPolicy.clientAddress(request),
                "and a proxy that sends no header does not produce a null address");
    }

    @Test
    void noAddressMeansNoSharedAddressBucket() {
        // A call that arrives without an HTTP request must not be counted against a
        // bucket every other such call shares, which is the fault being fixed.
        assertNull(LoginAttemptTracker.addressKey(null));
        for (int attempt = 1; attempt <= 5; attempt++) {
            signIn(ADMIN, null);
        }
        assertTrue(tracker.isLocked(LoginAttemptTracker.loginKey(ADMIN, null)));
        assertEquals(1, tracker.trackedKeys(), "only the username key was ever created");
    }
}
