package com.bms.service;

import com.bms.entity.SystemSetting;
import com.bms.repository.SystemSettingRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Where a sign-in came from, and how much that address is allowed to be wrong.
 *
 * <p>Both questions have to be answered outside the login endpoint because both need a
 * setting: a shop behind a proxy knows the real client address, and a shop that is not must
 * not pretend it does.
 */
@Component
public class LoginAttemptPolicy {

    private static final String TRUST_FORWARDED_FOR_KEY = "auth.trustForwardedFor";
    private static final String IP_MAX_FAILURES_KEY = "auth.ipMaxFailures";

    private final SystemSettingRepository systemSettingRepository;

    public LoginAttemptPolicy(SystemSettingRepository systemSettingRepository) {
        this.systemSettingRepository = systemSettingRepository;
    }

    /**
     * The address failures should be counted against, or {@code null} when there is no
     * address to hold them.
     *
     * <p>{@code X-Forwarded-For} is whatever the caller says it is, so it is only believed
     * when this install says it sits behind a proxy. Believing it by default would let a
     * script send a different address with every guess and never be locked at all - the
     * exact weakness the address count exists to close.
     */
    public String clientAddress(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String remote = blankToNull(request.getRemoteAddr());
        if (!isTrue(TRUST_FORWARDED_FOR_KEY)) {
            return remote;
        }
        String forwarded = blankToNull(request.getHeader("X-Forwarded-For"));
        if (forwarded == null) {
            return remote;
        }
        // The left-most entry is the client; everything after it is a proxy this app
        // has no way to check, and a chain of them would be one key per hop.
        String client = blankToNull(forwarded.split(",")[0]);
        return client != null ? client : remote;
    }

    /**
     * How many failures one address may cause across every username before the address is
     * shut, using the login window and lock length that the login page already uses.
     */
    public LoginAttemptTracker.Policy addressPolicy() {
        return new LoginAttemptTracker.Policy(
                positiveInt(IP_MAX_FAILURES_KEY, LoginAttemptTracker.ADDRESS.maxFailures()),
                LoginAttemptTracker.ADDRESS.window(),
                LoginAttemptTracker.ADDRESS.lockout());
    }

    private String settingValue(String key) {
        return systemSettingRepository.findBySettingKey(key)
                .map(SystemSetting::getSettingValue).orElse(null);
    }

    private boolean isTrue(String key) {
        String raw = settingValue(key);
        return raw != null && "true".equalsIgnoreCase(raw.trim());
    }

    private int positiveInt(String key, int fallback) {
        String raw = settingValue(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = new BigDecimal(raw.trim()).intValueExact();
            if (value <= 0) {
                throw new ArithmeticException("not positive");
            }
            return value;
        } catch (NumberFormatException | ArithmeticException ex) {
            return fallback;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
