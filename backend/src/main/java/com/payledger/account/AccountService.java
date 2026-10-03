package com.payledger.account;

import com.payledger.common.error.BusinessRuleViolationException;
import com.payledger.common.error.ResourceNotFoundException;
import com.payledger.security.Actor;
import com.payledger.security.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Accounts and who may see them. A customer sees only their own accounts; an operator sees everyone's. Someone
 * else's account is answered with 404 rather than 403, as GitHub does for a private repository: the caller learns
 * nothing about whether it exists (OWASP API Security Top 10, API1 Broken Object Level Authorization).
 */
@Service
public class AccountService {

    static final Set<String> SUPPORTED_CURRENCIES = Set.of("VND", "USD", "EUR");

    private final AccountRepository accounts;
    private final AccountEvents events;

    AccountService(AccountRepository accounts, AccountEvents events) {
        this.accounts = accounts;
        this.events = events;
    }

    /** Opens an account owned by {@code owner}, the signed-in customer. */
    @Transactional
    public Account open(Actor owner, String currency) {
        if (!SUPPORTED_CURRENCIES.contains(currency)) {
            throw new BusinessRuleViolationException("UNSUPPORTED_CURRENCY",
                    "Currency " + currency + " is not supported; expected one of " + SUPPORTED_CURRENCIES);
        }
        Account account = accounts.save(Account.open(owner.id(), currency));
        events.record(AccountEvents.OPENED, account, owner);
        return account;
    }

    @Transactional(readOnly = true)
    public Account get(Actor actor, UUID id) {
        Account account = accounts.findById(id).orElseThrow(() -> new ResourceNotFoundException("Account", id));
        if (!canSee(actor, account.getOwnerId())) {
            throw new ResourceNotFoundException("Account", id);
        }
        return account;
    }

    /** @param ownerId whose accounts; null for the caller's own */
    @Transactional(readOnly = true)
    public List<Account> list(Actor actor, String ownerId) {
        String owner = ownerId == null ? actor.id() : ownerId;
        if (!canSee(actor, owner)) {
            throw new AccessDeniedException("Customers can only list their own accounts");
        }
        return accounts.findByOwnerIdOrderByCreatedAtAsc(owner);
    }

    @Transactional
    public Account freeze(Actor actor, UUID id) {
        return transition(actor, id, Account::freeze, AccountEvents.FROZEN);
    }

    @Transactional
    public Account unfreeze(Actor actor, UUID id) {
        return transition(actor, id, Account::unfreeze, AccountEvents.UNFROZEN);
    }

    /** By the owner, or by an operator on the customer's behalf. */
    @Transactional
    public Account close(Actor actor, UUID id) {
        return transition(actor, id, Account::close, AccountEvents.CLOSED);
    }

    private static boolean canSee(Actor actor, String ownerId) {
        return actor.hasRole(Role.OPERATOR) || (actor.isUser() && actor.id().equals(ownerId));
    }

    private Account transition(Actor actor, UUID id, Consumer<Account> action, String eventType) {
        Account account = get(actor, id);
        action.accept(account);
        // saveAndFlush so the @Version check runs inside this transaction and a concurrent
        // status change surfaces as an optimistic-lock conflict instead of a lost update.
        Account saved = accounts.saveAndFlush(account);
        events.record(eventType, saved, actor);
        return saved;
    }
}
