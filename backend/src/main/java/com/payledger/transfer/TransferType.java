package com.payledger.transfer;

public enum TransferType {
    /** Money arriving from outside (e.g. a confirmed bank top-up): SYSTEM funding account → customer. */
    DEPOSIT,
    /** Customer-initiated movement between two customer accounts. */
    TRANSFER,
    /** Operator-initiated compensating movement that undoes a completed deposit or transfer. */
    REVERSAL
}
