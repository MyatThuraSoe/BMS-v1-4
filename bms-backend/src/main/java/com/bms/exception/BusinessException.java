package com.bms.exception;

public class BusinessException extends RuntimeException {

    private final Object[] args;

    public BusinessException(String message) {
        super(message);
        this.args = null;
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
        this.args = null;
    }

    public BusinessException(String message, Object... args) {
        super(message);
        this.args = args;
    }

    public Object[] getArgs() {
        return args;
    }
}
