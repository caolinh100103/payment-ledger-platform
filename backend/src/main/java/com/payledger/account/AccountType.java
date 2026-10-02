package com.payledger.account;

public enum AccountType {
    /** A customer wallet: what the platform owes the customer. Can never go negative. */
    CUSTOMER,
    /**
     * The platform's own side of a movement, e.g. the funding account that mirrors money held at the
     * partner bank. May go negative; never usable as an endpoint of a customer transfer.
     */
    SYSTEM
}
