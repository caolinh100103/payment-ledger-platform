package com.payledger.account;

import com.payledger.security.Actor;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Account API. Status changes are explicit actions (freeze/unfreeze/close) rather than a generic
 * PATCH, so each one has its own access rule.
 */
@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /** Opens an account for the signed-in customer; the owner comes from the access token, never the request. */
    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    @ApiResponse(responseCode = "201", description = "Opened")
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request, Actor actor) {
        Account account = accountService.open(actor, request.currency());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(account.getId()).toUri();
        return ResponseEntity.created(location).body(AccountResponse.from(account));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'OPERATOR')")
    public AccountResponse get(@PathVariable UUID id, Actor actor) {
        return AccountResponse.from(accountService.get(actor, id));
    }

    /** The caller's own accounts; an operator passes {@code ownerId} to look up a customer's. */
    @GetMapping
    @PreAuthorize("hasAnyRole('CUSTOMER', 'OPERATOR')")
    public List<AccountResponse> list(@RequestParam(required = false) String ownerId, Actor actor) {
        return accountService.list(actor, ownerId).stream().map(AccountResponse::from).toList();
    }

    @PostMapping("/{id}/freeze")
    @PreAuthorize("hasRole('OPERATOR')")
    public AccountResponse freeze(@PathVariable UUID id, Actor actor) {
        return AccountResponse.from(accountService.freeze(actor, id));
    }

    @PostMapping("/{id}/unfreeze")
    @PreAuthorize("hasRole('OPERATOR')")
    public AccountResponse unfreeze(@PathVariable UUID id, Actor actor) {
        return AccountResponse.from(accountService.unfreeze(actor, id));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'OPERATOR')")
    public AccountResponse close(@PathVariable UUID id, Actor actor) {
        return AccountResponse.from(accountService.close(actor, id));
    }

    public record OpenAccountRequest(
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO 4217 code, e.g. VND") String currency) {
    }

    /** {@code balance} is in the currency's minor unit (e.g. cents for USD). */
    public record AccountResponse(
            UUID id,
            String ownerId,
            String currency,
            AccountType type,
            AccountStatus status,
            long balance,
            Instant createdAt,
            Instant updatedAt) {

        static AccountResponse from(Account account) {
            return new AccountResponse(account.getId(), account.getOwnerId(), account.getCurrency(), account.getType(),
                    account.getStatus(), account.getBalance(), account.getCreatedAt(), account.getUpdatedAt());
        }
    }
}
