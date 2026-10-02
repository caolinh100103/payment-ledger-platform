package com.payledger.notification;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** One message to one customer. */
record Notification(UUID id, UUID eventId, String recipientId, Channel channel, String template, String message,
                    Instant createdAt) {

    enum Channel { SMS, EMAIL, PUSH }

    static Notification sms(UUID eventId, String recipientId, String template, String message) {
        return new Notification(UUID.randomUUID(), eventId, recipientId, Channel.SMS, template, message,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
}
