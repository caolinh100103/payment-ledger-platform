package com.payledger.transfer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Credits a customer with money confirmed to have arrived from outside the platform. In production this is
 * called by the bank / payment-gateway integration (e.g. on a top-up webhook), not by end users.
 */
@RestController
@RequestMapping("/api/v1/deposits")
public class DepositController {

    private final TransferService transferService;

    public DepositController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<Object> deposit(@Valid @RequestBody DepositRequest request) {
        Transfer deposit = transferService.deposit(request.accountId(), request.amount(), request.currency(),
                request.description());
        return TransferOutcomes.toResponse(deposit);
    }

    /**
     * {@code currency} is required even though the account has one: it makes the client state which minor
     * unit {@code amount} is in, so a mismatch is rejected instead of silently moving the wrong value.
     */
    public record DepositRequest(
            @NotNull UUID accountId,
            @Positive @Max(TransferLimits.MAX_AMOUNT) long amount,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO 4217 code, e.g. VND") String currency,
            @Size(max = 140) String description) {
    }
}
