package com.bms.entity;

/**
 * The kind of employee work shift. A cash drawer shift is the only kind that
 * may be attached to a physical drawer session and accept cash.
 */
public enum ShiftType {
    CASH_DRAWER,
    NON_CASH,
    SUPERVISOR
}
