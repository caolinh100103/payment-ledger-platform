package com.payledger.transfer;

import com.payledger.account.AccountService;
import com.payledger.security.Actor;
import com.payledger.transfer.AccountStatement.StatementPage;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@Tag(name = "Accounts")
class AccountStatementController {

    private final AccountService accountService;
    private final AccountStatement statement;

    AccountStatementController(AccountService accountService, AccountStatement statement) {
        this.accountService = accountService;
        this.statement = statement;
    }

    /**
     * The account's statement, newest first. The owner or an operator; someone else's account is 404, like a missing
     * one.
     */
    @GetMapping("/api/v1/accounts/{id}/ledger-entries")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'OPERATOR')")
    StatementPage ledgerEntries(@PathVariable UUID id,
                                @RequestParam(required = false) Long startingAfter,
                                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
                                Actor actor) {
        accountService.get(actor, id);
        return statement.page(id, startingAfter, limit);
    }
}
