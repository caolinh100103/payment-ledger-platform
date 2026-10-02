package com.payledger.transfer;

import com.payledger.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class ReversalApiIntegrationTest extends ApiTestSupport {

    @Test
    void reversalMovesTheMoneyBackWithCompensatingEntries() {
        String alice = fundedAccount("VND", 1_000_000);
        String bob = openAccount("VND");
        String original = jsonPath(transfer(alice, bob, 400_000, "VND"), "$.id");
        List<Long> originalEntryIds = entryIds(original);

        MvcTestResult result = reverse(original, "Chuyển nhầm người nhận");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo("REVERSAL");
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("COMPLETED");
        assertThat(result).bodyJson().extractingPath("$.reversalOf").isEqualTo(original);
        assertThat(result).bodyJson().extractingPath("$.sourceAccountId").isEqualTo(bob);
        assertThat(result).bodyJson().extractingPath("$.description").isEqualTo("Chuyển nhầm người nhận");
        assertThat(balanceOf(alice)).isEqualTo(1_000_000);
        assertThat(balanceOf(bob)).isZero();

        assertThat(mvc.get().uri("/api/v1/transfers/{id}", original))
                .bodyJson().extractingPath("$.status").isEqualTo("REVERSED");
        // The original entries are untouched; the reversal adds its own pair.
        assertThat(entryIds(original)).isEqualTo(originalEntryIds);
        assertThat(ledgerEntryCount(jsonPath(result, "$.id"))).isEqualTo(2);
    }

    @Test
    void depositCanBeReversed() {
        String alice = openAccount("EUR");
        String deposit = jsonPath(deposit(alice, 5_000, "EUR"), "$.id");

        assertThat(reverse(deposit, "Bank recalled the payment")).hasStatus(HttpStatus.CREATED);
        assertThat(balanceOf(alice)).isZero();
    }

    @Test
    void transferCanOnlyBeReversedOnce() {
        String alice = fundedAccount("VND", 100_000);
        String bob = openAccount("VND");
        String original = jsonPath(transfer(alice, bob, 10_000, "VND"), "$.id");
        reverse(original, "first");

        assertThat(reverse(original, "second"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.code").isEqualTo("TRANSFER_NOT_REVERSIBLE");
        assertThat(balanceOf(alice)).isEqualTo(100_000);
    }

    @Test
    void reversalCannotItselfBeReversed() {
        String alice = fundedAccount("VND", 100_000);
        String bob = openAccount("VND");
        String original = jsonPath(transfer(alice, bob, 10_000, "VND"), "$.id");
        String reversal = jsonPath(reverse(original, "mistake"), "$.id");

        assertThat(reverse(reversal, "undo the undo"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.code").isEqualTo("TRANSFER_NOT_REVERSIBLE");
    }

    @Test
    void failedTransferCannotBeReversed() {
        String alice = openAccount("VND");
        String bob = openAccount("VND");
        String failed = jsonPath(transfer(alice, bob, 10_000, "VND"), "$.transferId");

        assertThat(reverse(failed, "nothing to undo"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.code").isEqualTo("TRANSFER_NOT_REVERSIBLE");
    }

    @Test
    void reversalFailsWhenTheMoneyIsGoneAndCanBeRetriedLater() {
        String alice = fundedAccount("VND", 100_000);
        String bob = openAccount("VND");
        String carol = openAccount("VND");
        String original = jsonPath(transfer(alice, bob, 100_000, "VND"), "$.id");
        transfer(bob, carol, 60_000, "VND");

        MvcTestResult failed = reverse(original, "dispute");

        assertThat(failed).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.code").isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(mvc.get().uri("/api/v1/transfers/{id}", original))
                .bodyJson().extractingPath("$.status").isEqualTo("COMPLETED");
        assertThat(balanceOf(bob)).isEqualTo(40_000);

        transfer(carol, bob, 60_000, "VND");
        assertThat(reverse(original, "dispute, retried")).hasStatus(HttpStatus.CREATED);
        assertThat(balanceOf(alice)).isEqualTo(100_000);
    }

    @Test
    void fundsCanBeClawedBackFromAFrozenAccount() {
        String victim = fundedAccount("VND", 100_000);
        String fraudster = openAccount("VND");
        String original = jsonPath(transfer(victim, fraudster, 100_000, "VND"), "$.id");
        mvc.post().uri("/api/v1/accounts/{id}/freeze", fraudster).exchange();

        assertThat(reverse(original, "Fraud case #123")).hasStatus(HttpStatus.CREATED);
        assertThat(balanceOf(victim)).isEqualTo(100_000);
        assertThat(balanceOf(fraudster)).isZero();
    }

    @Test
    void concurrentReversalsOfTheSameTransferSucceedOnlyOnce() throws Exception {
        String alice = fundedAccount("VND", 100_000);
        String bob = openAccount("VND");
        String original = jsonPath(transfer(alice, bob, 50_000, "VND"), "$.id");
        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);

        List<Future<MvcTestResult>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return reverse(original, "operator double click");
                }));
            }
            start.countDown();
        }

        List<Integer> statuses = new ArrayList<>();
        for (Future<MvcTestResult> future : futures) {
            statuses.add(future.get().getMvcResult().getResponse().getStatus());
        }
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s != 201).containsOnly(422);
        assertThat(balanceOf(alice)).isEqualTo(100_000);
        assertThat(balanceOf(bob)).isZero();
    }

    @Test
    void returnsNotFoundForUnknownTransfer() {
        assertThat(reverse(UUID.randomUUID().toString(), "?")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void requiresAReason() {
        String alice = fundedAccount("VND", 100_000);
        String bob = openAccount("VND");
        String original = jsonPath(transfer(alice, bob, 10_000, "VND"), "$.id");

        assertThat(reverse(original, " ")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    private MvcTestResult reverse(String transferId, String reason) {
        return postWithKey("/api/v1/transfers/" + transferId + "/reversals", UUID.randomUUID().toString(), """
                {"reason": "%s"}
                """.formatted(reason));
    }

    private List<Long> entryIds(String transferId) {
        return jdbc.queryForList("SELECT id FROM ledger_entries WHERE transfer_id = ?::uuid ORDER BY id",
                Long.class, transferId);
    }
}
