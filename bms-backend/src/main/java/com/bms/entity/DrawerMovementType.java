package com.bms.entity;

/**
 * Every event that physically affects the cash drawer. Amounts are stored
 * signed: a positive amount increases expected drawer cash, a negative amount
 * decreases it. NO_SALE_OPEN is an audit-only event with amount zero.
 */
public enum DrawerMovementType {
    OPENING_FLOAT,
    CASH_SALE,
    CASH_REFUND,
    SAFE_DROP,
    PAY_IN,
    PAY_OUT,
    NO_SALE_OPEN,
    ADJUSTMENT
}
