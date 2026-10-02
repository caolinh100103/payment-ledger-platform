package com.payledger.account;

import com.payledger.common.error.BusinessRuleViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    @Test
    void newAccountIsActiveWithZeroBalance() {
        Account account = Account.open("user-1", "VND");

        assertThat(account.getId()).isNotNull();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getBalance()).isZero();
    }

    @Test
    void activeAccountCanBeFrozenAndUnfrozen() {
        Account account = Account.open("user-1", "VND");

        account.freeze();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.FROZEN);

        account.unfreeze();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void cannotFreezeAccountThatIsNotActive() {
        Account account = Account.open("user-1", "VND");
        account.freeze();

        assertThatThrownBy(account::freeze)
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting("code").isEqualTo("INVALID_ACCOUNT_STATUS_TRANSITION");
    }

    @Test
    void cannotUnfreezeActiveAccount() {
        Account account = Account.open("user-1", "VND");

        assertThatThrownBy(account::unfreeze)
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting("code").isEqualTo("INVALID_ACCOUNT_STATUS_TRANSITION");
    }

    @Test
    void frozenAccountWithZeroBalanceCanBeClosed() {
        Account account = Account.open("user-1", "VND");
        account.freeze();

        account.close();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
    }

    @Test
    void cannotCloseAccountWithFunds() {
        Account account = Account.open("user-1", "VND");
        ReflectionTestUtils.setField(account, "balance", 1_000L);

        assertThatThrownBy(account::close)
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting("code").isEqualTo("ACCOUNT_BALANCE_NOT_ZERO");
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void newAccountIsACustomerAccount() {
        assertThat(Account.open("user-1", "VND").getType()).isEqualTo(AccountType.CUSTOMER);
    }

    @Test
    void systemAccountStatusCannotBeChanged() {
        Account account = Account.open("system", "VND");
        ReflectionTestUtils.setField(account, "type", AccountType.SYSTEM);

        assertThatThrownBy(account::freeze)
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting("code").isEqualTo("SYSTEM_ACCOUNT_NOT_MODIFIABLE");
        assertThatThrownBy(account::close)
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting("code").isEqualTo("SYSTEM_ACCOUNT_NOT_MODIFIABLE");
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void closedAccountIsTerminal() {
        Account account = Account.open("user-1", "VND");
        account.close();

        assertThatThrownBy(account::close).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(account::unfreeze).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(account::freeze).isInstanceOf(BusinessRuleViolationException.class);
    }
}
