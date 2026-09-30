package com.bms.util;

/**
 * Frees a database-unique business key when a record is soft-deleted.
 * Soft deletes keep the row (for historical references), so a unique column value
 * would otherwise stay reserved forever and block re-adding the same SKU / username /
 * email / name with "already exists". Appending a suffix to the unique column on
 * delete restores the original value for reuse.
 */
public final class SoftDeleteKeys {

    private SoftDeleteKeys() {
    }

    /**
     * Return {@code value} suffixed with a stable marker so it no longer collides
     * with a new record using the original value. Null input stays null.
     *
     * @param value     the unique column value being released
     * @param id        the record id appended to the marker
     * @param maxLength the column's max length; the result is truncated to fit
     */
    public static String release(String value, Long id, int maxLength) {
        if (value == null) {
            return null;
        }
        String suffix = "#del_" + id;
        int keep = Math.max(1, maxLength - suffix.length());
        String base = value.length() > keep ? value.substring(0, keep) : value;
        return base + suffix;
    }
}