package com.payledger.audit;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** CloudEvents shaped like the ones the core service publishes. */
final class AuditTestEvents {

    private AuditTestEvents() {
    }

    static String cloudEvent(UUID id, String type, String subject) {
        return cloudEvent(id, type, subject, "anonymous");
    }

    static String cloudEvent(UUID id, String type, String subject, String actor) {
        return """
                {"specversion":"1.0","id":"%s","source":"/payledger/core","type":"%s","subject":"%s",\
                "time":"%s","datacontenttype":"application/json","schemaversion":1,"actor":"%s",\
                "data":{"transferId":"%s","amount":250000,"currency":"VND"}}"""
                .formatted(id, type, subject, Instant.now().truncatedTo(ChronoUnit.MICROS), actor, subject);
    }

    static AuditableEvent event(String subject) {
        UUID id = UUID.randomUUID();
        return new AuditableEvent(id, "anonymous", "com.payledger.transfer.completed", subject,
                Instant.now().truncatedTo(ChronoUnit.MICROS), "/payledger/core",
                cloudEvent(id, "com.payledger.transfer.completed", subject));
    }
}
