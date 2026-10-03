package com.payledger.ledger;

/** Every account's balance is credits minus debits: a DEBIT takes money out, a CREDIT puts money in. */
public enum EntryDirection {
    DEBIT,
    CREDIT
}
