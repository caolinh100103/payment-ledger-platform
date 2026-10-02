package com.payledger.common.error;

/**
 * A request that is well-formed but violates a domain rule (e.g. closing an account with funds).
 * Mapped to HTTP 422 with a stable, machine-readable {@code code}.
 */
public class BusinessRuleViolationException extends RuntimeException {

    private final String code;

    public BusinessRuleViolationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
