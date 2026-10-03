package com.payledger.account;

import com.payledger.outbox.CloudEvent;
import com.payledger.outbox.EventTopics;
import com.payledger.outbox.Outbox;
import com.payledger.security.Actor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Records account lifecycle changes in the outbox, inside the transaction that makes them. Freezing an account is a
 * privileged action with real consequences for the customer, so the audit trail must say who did it and when
 * (PCI DSS 10.2.1.2: all actions taken by anyone with administrative access).
 */
@Component
class AccountEvents {

    static final String OPENED = "com.payledger.account.opened";
    static final String FROZEN = "com.payledger.account.frozen";
    static final String UNFROZEN = "com.payledger.account.unfrozen";
    static final String CLOSED = "com.payledger.account.closed";

    private static final String SOURCE = "/payledger/core";
    private static final int SCHEMA_VERSION = 1;

    private final Outbox outbox;

    AccountEvents(Outbox outbox) {
        this.outbox = outbox;
    }

    void record(String type, Account account, Actor actor) {
        Data data = new Data(account.getId(), account.getOwnerId(), account.getCurrency(), account.getType(),
                account.getStatus());
        outbox.append(EventTopics.ACCOUNTS, "account", account.getId(),
                CloudEvent.of(SOURCE, type, SCHEMA_VERSION, account.getId().toString(), actor.name(), data));
    }

    /** The account after the change, schema version 1. Balances are not included: they belong to transfer events. */
    record Data(UUID accountId, String ownerId, String currency, AccountType accountType, AccountStatus status) {
    }
}
