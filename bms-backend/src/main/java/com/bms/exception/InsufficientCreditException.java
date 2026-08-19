package com.bms.exception;

/**
 * Thrown when a credit sale would push the customer's outstanding balance
 * (currentBalance + new invoice total) beyond their credit limit.
 */
public class InsufficientCreditException extends BusinessException {

    public InsufficientCreditException(String message, Object... args) {
        super(message, args);
    }

    public InsufficientCreditException(String message, Throwable cause) {
        super(message, cause);
    }
}