package com.payledger.transfer;

final class TransferLimits {

    /**
     * Upper bound for one movement in minor units (10^15). Far above any real payment, it keeps balance
     * arithmetic far away from {@code long} overflow. Per-customer limits (e.g. the monthly e-wallet cap set
     * by the State Bank of Vietnam) are a separate, configurable concern.
     */
    static final long MAX_AMOUNT = 1_000_000_000_000_000L;

    private TransferLimits() {
    }
}
