package com.payledger.transfer;

import java.time.Instant;
import java.util.UUID;

/** {@code amount} is in the currency's minor unit. */
public record TransferResponse(
        UUID id,
        TransferType type,
        TransferStatus status,
        UUID sourceAccountId,
        UUID destinationAccountId,
        long amount,
        String currency,
        String description,
        String failureCode,
        String failureReason,
        UUID reversalOf,
        Instant createdAt,
        Instant updatedAt) {

    static TransferResponse from(Transfer t) {
        return new TransferResponse(t.getId(), t.getType(), t.getStatus(), t.getSourceAccountId(),
                t.getDestinationAccountId(), t.getAmount(), t.getCurrency(), t.getDescription(),
                t.getFailureCode(), t.getFailureReason(), t.getReversalOf(), t.getCreatedAt(), t.getUpdatedAt());
    }
}
