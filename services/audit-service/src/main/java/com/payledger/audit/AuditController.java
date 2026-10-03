package com.payledger.audit;

import com.fasterxml.jackson.annotation.JsonRawValue;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only, and only for auditors (an ADMIN inherits the role). Nobody can write here; events arrive via Kafka. */
@RestController
@RequestMapping("/api/v1/audit-events")
@PreAuthorize("hasRole('AUDITOR')")
@Tag(name = "Audit events")
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

    /**
     * The latest events, newest first; with {@code actor} (e.g. {@code user:<id>}), only what that user or key did.
     * Pass the last {@code seq} of a page as {@code beforeSeq} for the next one.
     */
    @GetMapping("/latest")
    AuditEventPage latest(@RequestParam(required = false) @Size(max = 64) String actor,
                          @RequestParam(required = false) @Min(1) Long beforeSeq,
                          @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_RESULTS) int limit) {
        // One more than asked tells whether another page follows.
        List<AuditRecord> records = auditLog.latest(actor, beforeSeq, limit + 1);
        boolean hasMore = records.size() > limit;
        return new AuditEventPage(records.stream().limit(limit).map(AuditEventResponse::from).toList(), hasMore);
    }

    /** Re-hashes the whole chain. {@code valid: false} means a record was altered or removed. */
    @GetMapping("/verification")
    ChainVerification verify() {
        return auditLog.verify();
    }

    /** {@code event} is the CloudEvent exactly as it was received. */
    record AuditEventResponse(long seq, UUID eventId, String actor, String action,
                              @Schema(nullable = true) String resourceId,
                              Instant occurredAt, Instant recordedAt, String prevHash, String hash,
                              @JsonRawValue @Schema(implementation = Map.class, description = "The CloudEvent as received")
                              String event) {

        static AuditEventResponse from(AuditRecord r) {
            AuditableEvent e = r.event();
            return new AuditEventResponse(r.seq(), e.eventId(), e.actor(), e.action(), e.resourceId(), e.occurredAt(),
                    r.recordedAt(), r.prevHash(), r.hash(), e.payload());
        }
    }

    /** A page of events and whether more follow, as the core's lists. */
    record AuditEventPage(List<AuditEventResponse> data, boolean hasMore) {
    }
}
