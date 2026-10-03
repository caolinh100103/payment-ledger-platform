package com.payledger.common.idempotency;

import com.payledger.security.Role;
import com.payledger.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyApiIntegrationTest extends ApiTestSupport {

    @Autowired
    DataSource dataSource;

    @Test
    void retryWithSameKeyReplaysTheOriginalResponseAndMovesMoneyOnce() {
        String alice = fundedAccount("VND", 1_000_000);
        String bob = openAccount("VND");
        String key = UUID.randomUUID().toString();
        String body = transferBody(alice, bob, 300_000, "VND");

        MvcTestResult first = postWithKey("/api/v1/transfers", key, body, asOwnerOf(alice));
        MvcTestResult retry = postWithKey("/api/v1/transfers", key, body, asOwnerOf(alice));

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertThat(first).headers().doesNotContainHeader("Idempotent-Replayed");
        assertThat(retry).hasStatus(HttpStatus.CREATED).hasContentType(MediaType.APPLICATION_JSON);
        assertThat(retry).headers().hasValue("Idempotent-Replayed", "true");
        assertThat(retry).headers().hasValue("Location", first.getMvcResult().getResponse().getHeader("Location"));
        assertThat(bodyOf(retry)).isEqualTo(bodyOf(first)).contains("Tiền nhà tháng 10");
        assertThat(balanceOf(alice)).isEqualTo(700_000);
        assertThat(balanceOf(bob)).isEqualTo(300_000);
    }

    @Test
    void sameKeyForADifferentRequestIsRejected() {
        String alice = fundedAccount("VND", 1_000_000);
        String bob = openAccount("VND");
        String key = UUID.randomUUID().toString();
        postWithKey("/api/v1/transfers", key, transferBody(alice, bob, 1_000, "VND"), asOwnerOf(alice));

        MvcTestResult reused = postWithKey("/api/v1/transfers", key, transferBody(alice, bob, 2_000, "VND"),
                asOwnerOf(alice));

        assertThat(reused).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(balanceOf(alice)).isEqualTo(999_000);
    }

    @Test
    void sameKeyOnAnotherPathIsRejected() {
        String alice = fundedAccount("VND", 1_000_000);
        String bob = openAccount("VND");
        String first = jsonPath(transfer(alice, bob, 1_000, "VND"), "$.id");
        String second = jsonPath(transfer(alice, bob, 2_000, "VND"), "$.id");
        String operator = newCustomer();
        String key = UUID.randomUUID().toString();
        String sameBody = """
                {"reason": "Customer dispute"}
                """;
        postWithKey("/api/v1/transfers/" + first + "/reversals", key, sameBody, asUser(operator, Role.OPERATOR));

        assertThat(postWithKey("/api/v1/transfers/" + second + "/reversals", key, sameBody,
                asUser(operator, Role.OPERATOR)))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    /** As at Stripe, a key belongs to the client that sent it: another client picking the same key is unaffected. */
    @Test
    void keysAreScopedToTheClient() {
        String alice = fundedAccount("VND", 1_000_000);
        String bob = fundedAccount("VND", 1_000_000);
        String carol = openAccount("VND");
        String key = UUID.randomUUID().toString();

        MvcTestResult alicesTransfer = postWithKey("/api/v1/transfers", key, transferBody(alice, carol, 1_000, "VND"),
                asOwnerOf(alice));
        MvcTestResult bobsTransfer = postWithKey("/api/v1/transfers", key, transferBody(bob, carol, 2_000, "VND"),
                asOwnerOf(bob));

        assertThat(alicesTransfer).hasStatus(HttpStatus.CREATED);
        assertThat(bobsTransfer).hasStatus(HttpStatus.CREATED);
        assertThat(bobsTransfer).headers().doesNotContainHeader("Idempotent-Replayed");
        assertThat(balanceOf(carol)).isEqualTo(3_000);
        assertThat(jdbc.queryForList("SELECT scope FROM idempotency_keys WHERE idempotency_key = ?", String.class, key))
                .containsExactlyInAnyOrder("user:" + ownerOf(alice), "user:" + ownerOf(bob));
    }

    @Test
    void failedOutcomeIsReplayedEvenAfterTheSituationChanges() {
        String alice = openAccount("VND");
        String bob = openAccount("VND");
        String key = UUID.randomUUID().toString();
        String body = transferBody(alice, bob, 50_000, "VND");

        MvcTestResult first = postWithKey("/api/v1/transfers", key, body, asOwnerOf(alice));
        deposit(alice, 100_000, "VND");
        MvcTestResult retry = postWithKey("/api/v1/transfers", key, body, asOwnerOf(alice));

        assertThat(first).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.code").isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(retry).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(bodyOf(retry)).isEqualTo(bodyOf(first));
        // A new attempt needs a new key; the old outcome is final.
        assertThat(balanceOf(alice)).isEqualTo(100_000);
    }

    @Test
    void errorThatLeftNoTraceReleasesTheKey() {
        String alice = fundedAccount("VND", 100_000);
        String bob = openAccount("VND");
        String key = UUID.randomUUID().toString();

        assertThat(postWithKey("/api/v1/transfers", key, transferBody(alice, UUID.randomUUID().toString(), 1_000, "VND"),
                asOwnerOf(alice)))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(postWithKey("/api/v1/transfers", key, transferBody(alice, bob, 1_000, "VND"), asOwnerOf(alice)))
                .hasStatus(HttpStatus.CREATED);
    }

    @Test
    void requiresAWellFormedKey() {
        String alice = fundedAccount("VND", 100_000);
        String bob = openAccount("VND");

        assertThat(mvc.post().uri("/api/v1/transfers")
                .with(asOwnerOf(alice))
                .contentType(MediaType.APPLICATION_JSON)
                .content(transferBody(alice, bob, 1_000, "VND")))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("IDEMPOTENCY_KEY_MISSING");
        assertThat(postWithKey("/api/v1/transfers", "has spaces", transferBody(alice, bob, 1_000, "VND"),
                asOwnerOf(alice)))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("IDEMPOTENCY_KEY_INVALID");
        assertThat(postWithKey("/api/v1/transfers", "k".repeat(256), transferBody(alice, bob, 1_000, "VND"),
                asOwnerOf(alice)))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(balanceOf(alice)).isEqualTo(100_000);
    }

    @Test
    void secondRequestWhileTheFirstIsStillRunningGetsConflict() throws Exception {
        String alice = fundedAccount("VND", 100_000);
        String bob = openAccount("VND");
        String key = UUID.randomUUID().toString();
        String body = transferBody(alice, bob, 1_000, "VND");

        try (Connection other = dataSource.getConnection()) {
            // Hold Bob's row lock so the first request blocks mid-transaction while owning the key.
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement("SELECT 1 FROM accounts WHERE id = ?::uuid FOR UPDATE")) {
                lock.setString(1, bob);
                lock.executeQuery();
            }
            CompletableFuture<MvcTestResult> first = CompletableFuture.supplyAsync(
                    () -> postWithKey("/api/v1/transfers", key, body, asOwnerOf(alice)));
            awaitKeyClaimed(key);

            MvcTestResult second = postWithKey("/api/v1/transfers", key, body, asOwnerOf(alice));

            assertThat(second).hasStatus(HttpStatus.CONFLICT)
                    .bodyJson().extractingPath("$.code").isEqualTo("IDEMPOTENCY_KEY_IN_PROGRESS");
            assertThat(second).headers().hasValue("Retry-After", "1");

            other.rollback();
            assertThat(first.get()).hasStatus(HttpStatus.CREATED);
        }
        assertThat(balanceOf(alice)).isEqualTo(99_000);
    }

    @Test
    void doubleSubmitFromManyThreadsCreatesExactlyOneTransfer() throws Exception {
        String alice = fundedAccount("VND", 1_000_000);
        String bob = openAccount("VND");
        String key = UUID.randomUUID().toString();
        String body = transferBody(alice, bob, 10_000, "VND");
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);

        List<Future<MvcTestResult>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return postWithKey("/api/v1/transfers", key, body, asOwnerOf(alice));
                }));
            }
            start.countDown();
        }

        List<Integer> statuses = new ArrayList<>();
        for (Future<MvcTestResult> future : futures) {
            statuses.add(future.get().getMvcResult().getResponse().getStatus());
        }
        assertThat(statuses).containsOnly(201, 409).contains(201);
        assertThat(balanceOf(alice)).isEqualTo(990_000);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM transfers WHERE source_account_id = ?::uuid", Long.class, alice)).isEqualTo(1);
    }

    private void awaitKeyClaimed(String key) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Long claimed = jdbc.queryForObject(
                    "SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?", Long.class, key);
            if (claimed == 1) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Key was never claimed");
    }
}
