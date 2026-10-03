package com.payledger.transfer;

import com.payledger.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DepositApiIntegrationTest extends ApiTestSupport {

    @Test
    void depositDebitsSystemAccountAndCreditsCustomer() {
        String account = openAccount("VND");
        long systemBefore = balanceOf(systemAccountId("VND"));

        MvcTestResult result = deposit(account, 500_000, "VND");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).headers().containsHeader("Location");
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo("DEPOSIT");
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("COMPLETED");
        assertThat(result).bodyJson().extractingPath("$.sourceAccountId").isEqualTo(systemAccountId("VND"));
        assertThat(balanceOf(account)).isEqualTo(500_000);
        // Concurrent tests may also deposit VND, so only assert that the system account moved by at least ours.
        assertThat(balanceOf(systemAccountId("VND"))).isLessThanOrEqualTo(systemBefore - 500_000);

        String transferId = jsonPath(result, "$.id");
        List<Map<String, Object>> entries = jdbc.queryForList("""
                SELECT account_id::text AS account, direction, amount, balance_after
                FROM ledger_entries WHERE transfer_id = ?::uuid ORDER BY id
                """, transferId);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0)).containsEntry("account", systemAccountId("VND")).containsEntry("direction", "DEBIT");
        assertThat(entries.get(1)).containsEntry("account", account).containsEntry("direction", "CREDIT")
                .containsEntry("amount", 500_000L).containsEntry("balance_after", 500_000L);
    }

    @Test
    void depositIsReadableAsTransfer() {
        String account = openAccount("USD");
        String transferId = jsonPath(deposit(account, 1_999, "USD"), "$.id");

        assertThat(mvc.get().uri("/api/v1/transfers/{id}", transferId).with(asOperator()))
                .hasStatusOk()
                .bodyJson().extractingPath("$.amount").isEqualTo(1_999);
    }

    @Test
    void rejectedDepositIsRecordedAsFailedWithoutTouchingBalances() {
        String account = openAccount("VND");
        mvc.post().uri("/api/v1/accounts/{id}/freeze", account).with(asOperator()).exchange();

        MvcTestResult result = deposit(account, 10_000, "VND");

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("DESTINATION_ACCOUNT_NOT_ACTIVE");

        String transferId = jsonPath(result, "$.transferId");
        assertThat(mvc.get().uri("/api/v1/transfers/{id}", transferId).with(asOperator()))
                .hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("FAILED");
        assertThat(ledgerEntryCount(transferId)).isZero();
        assertThat(balanceOf(account)).isZero();
    }

    @Test
    void rejectsDepositInAnotherCurrencyThanTheAccount() {
        String account = openAccount("USD");

        assertThat(deposit(account, 10_000, "VND"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.code").isEqualTo("CURRENCY_MISMATCH");
    }

    @Test
    void rejectsDepositIntoSystemAccount() {
        assertThat(deposit(systemAccountId("USD"), 10_000, "VND"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.code").isEqualTo("ACCOUNT_TYPE_NOT_ALLOWED");
    }

    @Test
    void rejectsUnsupportedCurrency() {
        assertThat(deposit(openAccount("VND"), 10_000, "JPY"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.code").isEqualTo("UNSUPPORTED_CURRENCY");
    }

    @Test
    void returnsNotFoundForUnknownAccount() {
        assertThat(deposit(UUID.randomUUID().toString(), 10_000, "VND"))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsNonPositiveAmount() {
        assertThat(deposit(openAccount("VND"), 0, "VND")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(deposit(openAccount("VND"), -5, "VND")).hasStatus(HttpStatus.BAD_REQUEST);
    }
}
