package com.payledger.account;

import com.payledger.common.error.BusinessRuleViolationException;
import com.payledger.common.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class AccountService {

    static final Set<String> SUPPORTED_CURRENCIES = Set.of("VND", "USD", "EUR");

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional
    public Account open(String ownerId, String currency) {
        if (!SUPPORTED_CURRENCIES.contains(currency)) {
            throw new BusinessRuleViolationException("UNSUPPORTED_CURRENCY",
                    "Currency " + currency + " is not supported; expected one of " + SUPPORTED_CURRENCIES);
        }
        return accounts.save(Account.open(ownerId, currency));
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return accounts.findById(id).orElseThrow(() -> new ResourceNotFoundException("Account", id));
    }

    @Transactional(readOnly = true)
    public List<Account> listByOwner(String ownerId) {
        return accounts.findByOwnerIdOrderByCreatedAtAsc(ownerId);
    }

    @Transactional
    public Account freeze(UUID id) {
        return transition(id, Account::freeze);
    }

    @Transactional
    public Account unfreeze(UUID id) {
        return transition(id, Account::unfreeze);
    }

    @Transactional
    public Account close(UUID id) {
        return transition(id, Account::close);
    }

    private Account transition(UUID id, Consumer<Account> action) {
        Account account = get(id);
        action.accept(account);
        // saveAndFlush so the @Version check runs inside this transaction and a concurrent
        // status change surfaces as an optimistic-lock conflict instead of a lost update.
        return accounts.saveAndFlush(account);
    }
}
