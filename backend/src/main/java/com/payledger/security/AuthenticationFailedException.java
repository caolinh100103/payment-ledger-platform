package com.payledger.security;

import java.time.Duration;

/**
 * Credentials presented to a sign-in or token endpoint were not accepted. Mapped to 401 with a stable {@code code}.
 *
 * @see com.payledger.common.error.ApiExceptionHandler
 */
public class AuthenticationFailedException extends RuntimeException {

    private final String code;
    private final Duration retryAfter;

    public AuthenticationFailedException(String code, String message) {
        this(code, message, null);
    }

    public AuthenticationFailedException(String code, String message, Duration retryAfter) {
        super(message);
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public String getCode() {
        return code;
    }

    /** When trying again makes sense, e.g. when a lockout ends; null if not applicable. */
    public Duration getRetryAfter() {
        return retryAfter;
    }
}
