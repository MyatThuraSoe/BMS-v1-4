package com.bms.entity;

/**
 * Payment methods supported by the employee-shift / split-payment model.
 * The legacy {@link Sale.PaymentMethod} enum is retained only for old rows.
 */
public enum PaymentMethod {
    CASH,
    MOBILE_WALLET,
    CARD,
    BANK_TRANSFER,
    CREDIT,
    STORE_CREDIT,
    OTHER
}
