package com.payledger.audit;

/** The message is not a valid CloudEvent. Retrying cannot fix it, so it must not be retried. */
public class MalformedEventException extends RuntimeException {

    public MalformedEventException(String message) {
        super(message);
    }

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
