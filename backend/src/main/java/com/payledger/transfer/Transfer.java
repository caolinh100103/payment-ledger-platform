package com.payledger.transfer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** A money movement request and its outcome. The state machine is enforced here. */
@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private TransferType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransferStatus status;

    @Column(name = "source_account_id", nullable = false, updatable = false)
    private UUID sourceAccountId;

    @Column(name = "destination_account_id", nullable = false, updatable = false)
    private UUID destinationAccountId;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(updatable = false, length = 140)
    private String description;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "reversal_of", updatable = false)
    private UUID reversalOf;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Transfer() {
        // for JPA
    }

    static Transfer deposit(UUID systemAccountId, UUID destinationAccountId, long amount, String currency,
                            String description) {
        return create(TransferType.DEPOSIT, systemAccountId, destinationAccountId, amount, currency, description, null);
    }

    static Transfer transfer(UUID sourceAccountId, UUID destinationAccountId, long amount, String currency,
                             String description) {
        return create(TransferType.TRANSFER, sourceAccountId, destinationAccountId, amount, currency, description, null);
    }

    /** The compensating movement: same amount, opposite direction. */
    static Transfer reversalOf(Transfer original, String reason) {
        return create(TransferType.REVERSAL, original.destinationAccountId, original.sourceAccountId,
                original.amount, original.currency, reason, original.id);
    }

    private static Transfer create(TransferType type, UUID source, UUID destination, long amount, String currency,
                                   String description, UUID reversalOf) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive, got " + amount);
        }
        if (source.equals(destination)) {
            throw new IllegalArgumentException("Source and destination must differ");
        }
        Transfer transfer = new Transfer();
        transfer.id = UUID.randomUUID();
        transfer.type = type;
        transfer.status = TransferStatus.PENDING;
        transfer.sourceAccountId = source;
        transfer.destinationAccountId = destination;
        transfer.amount = amount;
        transfer.currency = currency;
        transfer.description = description;
        transfer.reversalOf = reversalOf;
        transfer.createdAt = now();
        transfer.updatedAt = transfer.createdAt;
        return transfer;
    }

    void complete() {
        requireStatus(TransferStatus.PENDING, "complete");
        status = TransferStatus.COMPLETED;
    }

    void fail(String code, String reason) {
        requireStatus(TransferStatus.PENDING, "fail");
        status = TransferStatus.FAILED;
        failureCode = code;
        failureReason = reason;
    }

    void markReversed() {
        requireStatus(TransferStatus.COMPLETED, "reverse");
        status = TransferStatus.REVERSED;
    }

    // Programming errors, not client errors: callers check reversibility before reaching this point.
    private void requireStatus(TransferStatus expected, String action) {
        if (status != expected) {
            throw new IllegalStateException("Cannot " + action + " transfer " + id + " in status " + status);
        }
    }

    @PreUpdate
    void touch() {
        updatedAt = now();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() {
        return id;
    }

    public TransferType getType() {
        return type;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public UUID getSourceAccountId() {
        return sourceAccountId;
    }

    public UUID getDestinationAccountId() {
        return destinationAccountId;
    }

    public long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public UUID getReversalOf() {
        return reversalOf;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
