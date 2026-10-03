package com.payledger.notification;

/** The message is not a valid transfer event. Retrying cannot fix it, so it is not retried. */
public class MalformedEventException extends RuntimeException {

    public MalformedEventException(String message) {
        super(message);
    }

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
