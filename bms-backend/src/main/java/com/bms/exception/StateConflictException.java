package com.bms.exception;

/**
 * A refusal because of the state the shop's own data is in — an invoice that was already
 * voided, a drawer that is still open, Google Drive that was never connected. The thrower
 * chose these words for the person at the counter, which makes this the one exception type
 * answered with its own message. A plain {@link IllegalStateException} is this process
 * complaining about itself, so its text belongs in the logs and not in the response.
 */
public class StateConflictException extends RuntimeException {

    public StateConflictException(String message) {
        super(message);
    }
}
