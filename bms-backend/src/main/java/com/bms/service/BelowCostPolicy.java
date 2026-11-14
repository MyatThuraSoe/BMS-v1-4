package com.bms.service;

/**
 * P10: how the POS treats a cart line whose effective price falls below the
 * product's cost. Configured via the {@code shop.belowCostPolicy} system setting.
 */
public enum BelowCostPolicy {
    ALLOW_WARN,
    REQUIRE_MANAGER,
    BLOCK;

    public static BelowCostPolicy fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALLOW_WARN;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ALLOW_WARN;
        }
    }
}