package com.bms.service;

import com.bms.entity.SystemSetting;
import com.bms.repository.SystemSettingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * O7: single source of truth for the shop's timezone and for day /
 * accounting-period boundaries derived from it.
 *
 * <p>The zone comes from the {@code shop.timezone} system setting (an IANA
 * zone id such as {@code Asia/Yangon}, or {@code UTC} by default), read through
 * the same {@link SystemSettingRepository} pattern as other shop settings.
 * The {@code shop.timezone} property in application.yml is only the bootstrap
 * fallback used when the setting row is missing or holds an invalid zone.
 *
 * <p>Storage convention: entity timestamps remain {@link LocalDateTime}
 * wall-clock values in the shop zone (converting every column to Instant is
 * deliberately out of scope). All "today" / "now" anchors and day-boundary
 * derivations must therefore go through this component instead of
 * {@code LocalDate.now()} / {@code LocalDateTime.now()}, which silently use
 * the JVM default zone.
 */
@Component
public class ShopTimeService {

    public static final String TIMEZONE_KEY = "shop.timezone";
    public static final String DEFAULT_TIMEZONE = "UTC";

    private final SystemSettingRepository systemSettingRepository;
    private final String fallbackTimezone;

    public ShopTimeService(SystemSettingRepository systemSettingRepository,
                           @Value("${shop.timezone:}") String fallbackTimezone) {
        this.systemSettingRepository = systemSettingRepository;
        String normalized = (fallbackTimezone == null || fallbackTimezone.isBlank())
                ? ZoneId.systemDefault().getId()
                : fallbackTimezone.trim();
        if (systemSettingRepository == null && DEFAULT_TIMEZONE.equalsIgnoreCase(normalized)) {
            normalized = ZoneId.systemDefault().getId();
        }
        this.fallbackTimezone = normalized;
    }

    /** The shop zone; never null. Invalid or missing settings fall back to the configured default (UTC). */
    public ZoneId zone() {
        String configured = systemSettingRepository != null
                ? systemSettingRepository.findBySettingKey(TIMEZONE_KEY)
                        .map(SystemSetting::getSettingValue)
                        .orElse(null)
                : null;
        ZoneId zone = parseZone(configured);
        if (zone != null) {
            return zone;
        }
        zone = parseZone(fallbackTimezone);
        return zone != null ? zone : ZoneId.of(DEFAULT_TIMEZONE);
    }

    /** Current shop-local date — the anchor for per-day keys and day reports. */
    public LocalDate today() {
        return LocalDate.now(zone());
    }

    /** Current shop-local wall-clock timestamp. */
    public LocalDateTime now() {
        return LocalDateTime.now(zone());
    }

    /**
     * The shop-local calendar date of a stored wall-clock timestamp. Stored
     * LocalDateTime values are shop-zone wall clock by convention, so this is
     * a plain date extraction — never a JVM-default-zone conversion.
     */
    public LocalDate toShopDate(LocalDateTime at) {
        return at.toLocalDate();
    }

    /** Inclusive start of the shop day: [date 00:00, ...). */
    public LocalDateTime startOfDay(LocalDate date) {
        return date.atStartOfDay();
    }

    /** Exclusive end of the shop day: [..., date+1 00:00). Prefer half-open ranges. */
    public LocalDateTime endOfDayExclusive(LocalDate date) {
        return date.plusDays(1).atStartOfDay();
    }

    /** Inclusive end of the shop day (23:59:59.999999999) for legacy BETWEEN-style queries. */
    public LocalDateTime endOfDayInclusive(LocalDate date) {
        return date.atTime(LocalTime.MAX);
    }

    /** Accounting period key ("yyyy-MM") for a shop-local date. */
    public String periodOf(LocalDate date) {
        return YearMonth.from(date).toString();
    }

    /** Accounting period key ("yyyy-MM") for a stored wall-clock timestamp. */
    public String periodOf(LocalDateTime at) {
        return periodOf(toShopDate(at));
    }

    private ZoneId parseZone(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(value.trim());
        } catch (Exception ex) {
            return null;
        }
    }
}