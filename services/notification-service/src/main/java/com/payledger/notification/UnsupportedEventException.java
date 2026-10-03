package com.payledger.notification;

/**
 * A well-formed event in a schema version this service does not know. Retrying cannot fix it: the service has to
 * be upgraded, so the event is parked for a human instead of being skipped.
 */
public class UnsupportedEventException extends RuntimeException {

    public UnsupportedEventException(String message) {
        super(message);
    }
}
