package com.payledger.audit;

import com.fasterxml.jackson.annotation.JsonRawValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Temporary: open to any caller until Phase 4 restricts it to the AUDITOR role.
@RestController
@RequestMapping("/api/v1/audit-events")
class AuditController {

    private static final int MAX_RESULTS = 200;

    private final AuditLog auditLog;

    AuditController(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    /** The trail of one resource (e.g. a transfer id), oldest first. */
    @GetMapping
    List<AuditEventResponse> byResource(@RequestParam String resourceId) {
        return auditLog.findByResource(resourceId, MAX_RESULTS).stream().map(AuditEventResponse::from).toList();
    }

    /** Re-hashes the whole chain. {@code valid: false} means a record was altered or removed. */
    @GetMapping("/verification")
    ChainVerification verify() {
        return auditLog.verify();
    }

    /** {@code event} is the CloudEvent exactly as it was received. */
    record AuditEventResponse(long seq, UUID eventId, String actor, String action, String resourceId,
                              Instant occurredAt, Instant recordedAt, String prevHash, String hash,
                              @JsonRawValue String event) {

        static AuditEventResponse from(AuditRecord r) {
            AuditableEvent e = r.event();
            return new AuditEventResponse(r.seq(), e.eventId(), e.actor(), e.action(), e.resourceId(), e.occurredAt(),
                    r.recordedAt(), r.prevHash(), r.hash(), e.payload());
        }
    }
}
