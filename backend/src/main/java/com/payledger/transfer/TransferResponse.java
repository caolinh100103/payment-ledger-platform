package com.payledger.transfer;

import io.swagger.v3.oas.annotations.media.Schema;

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
        @Schema(nullable = true) String description,
        @Schema(nullable = true, description = "Why it FAILED, e.g. INSUFFICIENT_FUNDS") String failureCode,
        @Schema(nullable = true) String failureReason,
        @Schema(nullable = true, description = "For a REVERSAL: the movement it reverses") UUID reversalOf,
        Instant createdAt,
        Instant updatedAt) {

    static TransferResponse from(Transfer t) {
        return new TransferResponse(t.getId(), t.getType(), t.getStatus(), t.getSourceAccountId(),
                t.getDestinationAccountId(), t.getAmount(), t.getCurrency(), t.getDescription(),
                t.getFailureCode(), t.getFailureReason(), t.getReversalOf(), t.getCreatedAt(), t.getUpdatedAt());
    }
}
