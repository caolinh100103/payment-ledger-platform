package com.payledger.account;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
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
 * PATCH so each one can later get its own RBAC rule and audit event.
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request) {
        Account account = accountService.open(request.ownerId(), request.currency());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(account.getId()).toUri();
        return ResponseEntity.created(location).body(AccountResponse.from(account));
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.from(accountService.get(id));
    }

    // Temporary: ownerId comes from the query until JWT auth (Phase 4) supplies the caller's identity.
    @GetMapping
    public List<AccountResponse> listByOwner(@RequestParam String ownerId) {
        return accountService.listByOwner(ownerId).stream().map(AccountResponse::from).toList();
    }

    @PostMapping("/{id}/freeze")
    public AccountResponse freeze(@PathVariable UUID id) {
        return AccountResponse.from(accountService.freeze(id));
    }

    @PostMapping("/{id}/unfreeze")
    public AccountResponse unfreeze(@PathVariable UUID id) {
        return AccountResponse.from(accountService.unfreeze(id));
    }

    @PostMapping("/{id}/close")
    public AccountResponse close(@PathVariable UUID id) {
        return AccountResponse.from(accountService.close(id));
    }

    public record OpenAccountRequest(
            @NotBlank @Size(max = 64) String ownerId,
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
