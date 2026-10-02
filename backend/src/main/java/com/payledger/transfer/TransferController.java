package com.payledger.transfer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * 201 with the COMPLETED transfer, or 422 with a stable {@code code} (e.g. INSUFFICIENT_FUNDS) when a
     * business rule rejects it; the rejected attempt is still recorded and returned as {@code transferId}.
     */
    // Temporary: the caller may debit any account until JWT auth (Phase 4) checks ownership of the source.
    @PostMapping
    public ResponseEntity<Object> create(@Valid @RequestBody CreateTransferRequest request) {
        Transfer transfer = transferService.transfer(request.sourceAccountId(), request.destinationAccountId(),
                request.amount(), request.currency(), request.description());
        return TransferOutcomes.toResponse(transfer);
    }

    @GetMapping("/{id}")
    public TransferResponse get(@PathVariable UUID id) {
        return TransferResponse.from(transferService.get(id));
    }

    /** {@code amount} is in the minor unit of {@code currency}, which must match both accounts. */
    public record CreateTransferRequest(
            @NotNull UUID sourceAccountId,
            @NotNull UUID destinationAccountId,
            @Positive @Max(TransferLimits.MAX_AMOUNT) long amount,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO 4217 code, e.g. VND") String currency,
            @Size(max = 140) String description) {
    }
}
