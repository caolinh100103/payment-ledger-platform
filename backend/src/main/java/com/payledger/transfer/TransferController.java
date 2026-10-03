package com.payledger.transfer;

import com.payledger.common.idempotency.IdempotencyHandler;
import com.payledger.common.idempotency.IdempotencyKey;
import com.payledger.security.Actor;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers")
public class TransferController {

    private final TransferService transferService;
    private final IdempotencyHandler idempotency;

    public TransferController(TransferService transferService, IdempotencyHandler idempotency) {
        this.transferService = transferService;
        this.idempotency = idempotency;
    }

    /**
     * 201 with the COMPLETED transfer, or 422 with a stable {@code code} (e.g. INSUFFICIENT_FUNDS) when a
     * business rule rejects it; the rejected attempt is still recorded and returned as {@code transferId}.
     * Requires an {@code Idempotency-Key}: retrying with the same key returns the original outcome.
     * The source account must belong to the caller.
     */
    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    @ApiResponse(responseCode = "201", description = "Completed; a retry with the same key replays it",
            content = @Content(schema = @Schema(implementation = TransferResponse.class)))
    @ApiResponse(responseCode = "422",
            description = "Rejected by a business rule and recorded as FAILED (`transferId`); a retry replays it",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "Problem")))
    public ResponseEntity<String> create(@IdempotencyKey
                                         @RequestHeader(name = IdempotencyHandler.HEADER, required = false) String key,
                                         @Valid @RequestBody CreateTransferRequest request,
                                         HttpServletRequest http, Actor actor) {
        return idempotency.execute(key, http, request, () -> TransferOutcomes.toResponse(
                transferService.transfer(actor, request.sourceAccountId(), request.destinationAccountId(),
                        request.amount(), request.currency(), request.description())));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'OPERATOR')")
    public TransferResponse get(@PathVariable UUID id, Actor actor) {
        return TransferResponse.from(transferService.get(actor, id));
    }

    /**
     * Reverses a COMPLETED deposit or transfer in full. 201 with the REVERSAL transfer; 422
     * TRANSFER_NOT_REVERSIBLE if it is not COMPLETED; 422 with a FAILED reversal (e.g. INSUFFICIENT_FUNDS)
     * if the money can no longer be taken back.
     */
    @PostMapping("/{id}/reversals")
    @PreAuthorize("hasRole('OPERATOR')")
    @ApiResponse(responseCode = "201", description = "The completed REVERSAL",
            content = @Content(schema = @Schema(implementation = TransferResponse.class)))
    @ApiResponse(responseCode = "422",
            description = "Not reversible, or the reversal was rejected and recorded as FAILED",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "Problem")))
    public ResponseEntity<String> reverse(@IdempotencyKey
                                          @RequestHeader(name = IdempotencyHandler.HEADER, required = false) String key,
                                          @PathVariable UUID id,
                                          @Valid @RequestBody ReverseTransferRequest request,
                                          HttpServletRequest http, Actor actor) {
        return idempotency.execute(key, http, request,
                () -> TransferOutcomes.toResponse(transferService.reverse(actor, id, request.reason())));
    }

    /** {@code reason} is kept as the reversal's description for the audit trail. */
    public record ReverseTransferRequest(@NotBlank @Size(max = 140) String reason) {
    }

    /** {@code amount} is in the minor unit of {@code currency}, which must match both accounts. */
    public record CreateTransferRequest(
            @NotNull UUID sourceAccountId,
            @NotNull UUID destinationAccountId,
            @Positive @Max(TransferLimits.MAX_AMOUNT) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long amount,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO 4217 code, e.g. VND") String currency,
            @Size(max = 140) String description) {
    }
}
