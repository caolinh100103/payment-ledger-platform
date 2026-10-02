package com.payledger.account;

public enum AccountStatus {
    /** Can send and receive funds. */
    ACTIVE,
    /** Temporarily blocked (e.g. suspected fraud); no money movement allowed. */
    FROZEN,
    /** Terminal state; only reachable with a zero balance. */
    CLOSED
}
