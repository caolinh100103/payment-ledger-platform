package com.payledger.common.idempotency;

import org.springframework.http.HttpStatus;

/** A request whose {@code Idempotency-Key} cannot be honoured; mapped to a problem with a stable code. */
public class IdempotencyException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    IdempotencyException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
