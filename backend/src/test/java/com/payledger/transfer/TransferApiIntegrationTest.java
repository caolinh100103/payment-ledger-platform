package com.payledger.transfer;

import com.payledger.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransferApiIntegrationTest extends ApiTestSupport {

    @Autowired
    DataSource dataSource;

    @Test
    void movesMoneyBetweenCustomersWithBalancedEntries() {
        String alice = fundedAccount("VND", 1_000_000);
        String bob = openAccount("VND");

        MvcTestResult result = transfer(alice, bob, 250_000, "VND");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo("TRANSFER");
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("COMPLETED");
        assertThat(result).bodyJson().extractingPath("$.description").isEqualTo("Tiền nhà tháng 10");
        assertThat(balanceOf(alice)).isEqualTo(750_000);
        assertThat(balanceOf(bob)).isEqualTo(250_000);

        List<Map<String, Object>> entries = jdbc.queryForList("""
                SELECT account_id::text AS account, direction, amount, balance_after
                FROM ledger_entries WHERE transfer_id = ?::uuid ORDER BY id
                """, (String) jsonPath(result, "$.id"));
        assertThat(entries).containsExactly(
                Map.of("account", alice, "direction", "DEBIT", "amount", 250_000L, "balance_after", 750_000L),
                Map.of("account", bob, "direction", "CREDIT", "amount", 250_000L, "balance_after", 250_000L));
    }

    @Test
    void canSpendTheExactBalance() {
        String alice = fundedAccount("USD", 1_000);
        String bob = openAccount("USD");

        assertThat(transfer(alice, bob, 1_000, "USD")).hasStatus(HttpStatus.CREATED);
        assertThat(balanceOf(alice)).isZero();
    }

    @Test
    void insufficientFundsIsRecordedAsFailed() {
        String alice = fundedAccount("VND", 100_000);
        String bob = openAccount("VND");

        MvcTestResult result = transfer(alice, bob, 100_001, "VND");

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INSUFFICIENT_FUNDS");
        String transferId = jsonPath(result, "$.transferId");
        assertThat(mvc.get().uri("/api/v1/transfers/{id}", transferId).with(asOwnerOf(alice)))
                .hasStatusOk()
                .bodyJson().extractingPath("$.failureCode").isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(ledgerEntryCount(transferId)).isZero();
        assertThat(balanceOf(alice)).isEqualTo(100_000);
        assertThat(balanceOf(bob)).isZero();
    }

    @Test
    void frozenAccountCannotSendOrReceive() {
        String alice = fundedAccount("VND", 100_000);
        String bob = fundedAccount("VND", 100_000);
        mvc.post().uri("/api/v1/accounts/{id}/freeze", bob).with(asOperator()).exchange();

        assertThat(transfer(bob, alice, 1, "VND"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.code").isEqualTo("SOURCE_ACCOUNT_NOT_ACTIVE");
        assertThat(transfer(alice, bob, 1, "VND"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.code").isEqualTo("DESTINATION_ACCOUNT_NOT_ACTIVE");
    }

    @Test
    void rejectsCurrencyMismatch() {
        String vnd = fundedAccount("VND", 100_000);
        String usd = openAccount("USD");

        assertThat(transfer(vnd, usd, 1_000, "VND"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.code").isEqualTo("CURRENCY_MISMATCH");
    }

    @Test
    void customerCannotDrainOrFeedASystemAccount() {
        String alice = fundedAccount("VND", 100_000);

        // Nobody owns a SYSTEM account, so it is not even found as a source.
        assertThat(postWithKey("/api/v1/transfers", UUID.randomUUID().toString(),
                transferBody(systemAccountId("VND"), alice, 1_000_000, "VND"), asOwnerOf(alice)))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(transfer(alice, systemAccountId("VND"), 1_000, "VND"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.code").isEqualTo("ACCOUNT_TYPE_NOT_ALLOWED");
        assertThat(balanceOf(alice)).isEqualTo(100_000);
    }

    @Test
    void rejectsTransferToSameAccount() {
        String alice = fundedAccount("VND", 100_000);

        assertThat(transfer(alice, alice, 1_000, "VND"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.code").isEqualTo("SAME_ACCOUNT");
    }

    @Test
    void returnsNotFoundForUnknownAccount() {
        String alice = fundedAccount("VND", 100_000);

        assertThat(transfer(alice, UUID.randomUUID().toString(), 1_000, "VND"))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsInvalidPayload() {
        assertThat(mvc.post().uri("/api/v1/transfers")
                .with(asCustomer(newCustomer()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sourceAccountId": null, "amount": 0, "currency": "vnd", "description": "%s"}
                        """.formatted("x".repeat(141))))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void failsFastWithRetryableErrorWhenAnAccountStaysLocked() throws Exception {
        String alice = fundedAccount("VND", 100_000);
        String bob = openAccount("VND");

        // Another transaction holds Bob's row lock for longer than lock_timeout.
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement("SELECT 1 FROM accounts WHERE id = ?::uuid FOR UPDATE")) {
                lock.setString(1, bob);
                lock.executeQuery();
            }

            MvcTestResult result = transfer(alice, bob, 1_000, "VND");

            assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(result).headers().hasValue("Retry-After", "1");
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("LOCK_TIMEOUT");
            other.rollback();
        }
        assertThat(balanceOf(alice)).isEqualTo(100_000);

        assertThat(transfer(alice, bob, 1_000, "VND")).hasStatus(HttpStatus.CREATED);
    }
}
