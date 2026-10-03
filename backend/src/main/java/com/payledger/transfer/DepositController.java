package com.payledger.transfer;

import com.payledger.common.idempotency.IdempotencyHandler;
import com.payledger.security.Actor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Credits a customer with money confirmed to have arrived from outside the platform. In production this is
 * called by the bank / payment-gateway integration (e.g. on a top-up webhook), not by end users. Webhooks
 * are delivered at least once, so the integration sends the bank's transaction reference as the
 * {@code Idempotency-Key} and a redelivered notification never credits the customer twice.
 *
 * <p>Only a machine client holding an API key with the {@code deposits:write} scope may call it. No person can,
 * not even an ADMIN: a deposit creates customer money, and only the bank knows that money really arrived.
 */
@RestController
@RequestMapping("/api/v1/deposits")
public class DepositController {

    private final TransferService transferService;
    private final IdempotencyHandler idempotency;

    public DepositController(TransferService transferService, IdempotencyHandler idempotency) {
        this.transferService = transferService;
        this.idempotency = idempotency;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_deposits:write')")
    public ResponseEntity<String> deposit(@RequestHeader(name = IdempotencyHandler.HEADER, required = false) String key,
                                          @Valid @RequestBody DepositRequest request,
                                          HttpServletRequest http, Actor actor) {
        return idempotency.execute(key, http, request, () -> TransferOutcomes.toResponse(
                transferService.deposit(actor, request.accountId(), request.amount(), request.currency(),
                        request.description())));
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
