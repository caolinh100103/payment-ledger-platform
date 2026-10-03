package com.payledger.transfer;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    void startsPendingAndCompletes() {
        Transfer transfer = Transfer.transfer(a, b, 100, "VND", "rent");
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);

        transfer.complete();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(transfer.getFailureCode()).isNull();
    }

    @Test
    void failureKeepsCodeAndReason() {
        Transfer transfer = Transfer.transfer(a, b, 100, "VND", null);

        transfer.fail("INSUFFICIENT_FUNDS", "not enough");

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(transfer.getFailureCode()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void onlyCompletedTransfersCanBeReversed() {
        Transfer failed = Transfer.transfer(a, b, 100, "VND", null);
        failed.fail("INSUFFICIENT_FUNDS", "not enough");
        assertThatThrownBy(failed::markReversed).isInstanceOf(IllegalStateException.class);

        Transfer completed = Transfer.transfer(a, b, 100, "VND", null);
        completed.complete();
        completed.markReversed();
        assertThat(completed.getStatus()).isEqualTo(TransferStatus.REVERSED);
        assertThatThrownBy(completed::markReversed).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reversalMovesTheSameAmountTheOtherWay() {
        Transfer original = Transfer.transfer(a, b, 100, "VND", null);

        Transfer reversal = Transfer.reversalOf(original, "customer dispute");

        assertThat(reversal.getType()).isEqualTo(TransferType.REVERSAL);
        assertThat(reversal.getSourceAccountId()).isEqualTo(b);
        assertThat(reversal.getDestinationAccountId()).isEqualTo(a);
        assertThat(reversal.getAmount()).isEqualTo(100);
        assertThat(reversal.getReversalOf()).isEqualTo(original.getId());
    }

    @Test
    void rejectsInvalidAmountsAndSameAccount() {
        assertThatThrownBy(() -> Transfer.transfer(a, b, 0, "VND", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Transfer.transfer(a, a, 1, "VND", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
