package com.payledger.transfer;

import com.payledger.account.Account;
import com.payledger.account.AccountType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class TransferRulesTest {

    private final Account alice = customer("VND", 1_000);
    private final Account bob = customer("VND", 0);

    @Test
    void acceptsValidTransfer() {
        assertThat(TransferRules.check(transfer(alice, bob, 1_000), alice, bob)).isEmpty();
    }

    @Test
    void rejectsInsufficientFunds() {
        assertThat(TransferRules.check(transfer(alice, bob, 1_001), alice, bob))
                .hasValueSatisfying(r -> assertThat(r.code()).isEqualTo("INSUFFICIENT_FUNDS"));
    }

    @Test
    void rejectsFrozenSourceOrDestination() {
        alice.freeze();
        assertThat(TransferRules.check(transfer(alice, bob, 1), alice, bob))
                .hasValueSatisfying(r -> assertThat(r.code()).isEqualTo("SOURCE_ACCOUNT_NOT_ACTIVE"));

        assertThat(TransferRules.check(transfer(bob, alice, 1), bob, alice))
                .hasValueSatisfying(r -> assertThat(r.code()).isEqualTo("DESTINATION_ACCOUNT_NOT_ACTIVE"));
    }

    @Test
    void rejectsCurrencyMismatch() {
        Account usd = customer("USD", 0);
        assertThat(TransferRules.check(transfer(alice, usd, 1), alice, usd))
                .hasValueSatisfying(r -> assertThat(r.code()).isEqualTo("CURRENCY_MISMATCH"));
    }

    @Test
    void customerCannotMoveMoneyOutOfASystemAccount() {
        Account system = system("VND");
        assertThat(TransferRules.check(transfer(system, bob, 1_000_000), system, bob))
                .hasValueSatisfying(r -> assertThat(r.code()).isEqualTo("ACCOUNT_TYPE_NOT_ALLOWED"));
    }

    @Test
    void depositMayOverdrawTheSystemAccount() {
        Account system = system("VND");
        Transfer deposit = Transfer.deposit(system.getId(), bob.getId(), 5_000, "VND", null);

        assertThat(TransferRules.check(deposit, system, bob)).isEmpty();
    }

    @Test
    void reversalIsAllowedOnFrozenAccounts() {
        Transfer original = transfer(alice, bob, 500);
        Transfer reversal = Transfer.reversalOf(original, "fraud");
        ReflectionTestUtils.setField(bob, "balance", 500L);

        bob.freeze();
        assertThat(TransferRules.check(reversal, bob, alice)).isEmpty();
    }

    @Test
    void reversalIsRejectedWhenAnAccountIsClosed() {
        Account empty = customer("VND", 0);
        Transfer original = transfer(empty, bob, 500);
        Transfer reversal = Transfer.reversalOf(original, "fraud");
        ReflectionTestUtils.setField(bob, "balance", 500L);
        empty.close();

        assertThat(TransferRules.check(reversal, bob, empty))
                .hasValueSatisfying(r -> assertThat(r.code()).isEqualTo("DESTINATION_ACCOUNT_NOT_ACTIVE"));
    }

    @Test
    void reversalStillNeedsFundsOnACustomerAccount() {
        Transfer original = transfer(alice, bob, 500);
        Transfer reversal = Transfer.reversalOf(original, "fraud");

        assertThat(TransferRules.check(reversal, bob, alice))
                .hasValueSatisfying(r -> assertThat(r.code()).isEqualTo("INSUFFICIENT_FUNDS"));
    }

    private static Transfer transfer(Account from, Account to, long amount) {
        return Transfer.transfer(from.getId(), to.getId(), amount, "VND", null);
    }

    private static Account customer(String currency, long balance) {
        Account account = Account.open("owner", currency);
        ReflectionTestUtils.setField(account, "balance", balance);
        return account;
    }

    private static Account system(String currency) {
        Account account = Account.open("system", currency);
        ReflectionTestUtils.setField(account, "type", AccountType.SYSTEM);
        return account;
    }
}
