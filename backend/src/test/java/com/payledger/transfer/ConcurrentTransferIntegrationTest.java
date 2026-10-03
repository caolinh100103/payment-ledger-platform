package com.payledger.transfer;

import com.jayway.jsonpath.JsonPath;
import com.payledger.TestcontainersConfiguration;
import com.payledger.security.Role;
import com.payledger.security.token.AccessTokens;
import com.payledger.support.LedgerInvariants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many clients hitting the real HTTP server at once: real Tomcat threads, a real connection pool and real
 * PostgreSQL row locks. Proves no overdraft, no lost update, no deadlock and a balanced ledger.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ConcurrentTransferIntegrationTest {

    private static final int THREADS = 100;

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AccessTokens accessTokens;

    private HttpClient http;
    private String accessToken;
    private long deadlocksBefore;

    @BeforeEach
    void setUp() {
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        accessToken = accessTokens.issue(UUID.randomUUID(), Role.OPERATOR).value();
        deadlocksBefore = deadlockCount();
    }

    @AfterEach
    void ledgerStaysConsistent() {
        LedgerInvariants.assertHold(jdbc);
        assertThat(deadlockCount()).as("deadlocks detected by PostgreSQL").isEqualTo(deadlocksBefore);
        http.close();
    }

    @Test
    void randomTransfersInBothDirectionsKeepEveryInvariant() throws Exception {
        int accountCount = 10;
        long initialBalance = 1_000_000;
        List<String> accounts = new ArrayList<>();
        for (int i = 0; i < accountCount; i++) {
            accounts.add(fundedAccount(initialBalance));
        }
        Map<Integer, AtomicInteger> statusCounts = new ConcurrentHashMap<>();

        runConcurrently(THREADS, thread -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < 20; i++) {
                int from = random.nextInt(accountCount);
                int to = (from + 1 + random.nextInt(accountCount - 1)) % accountCount;
                // Up to 40% of a starting balance, so overdraft attempts are frequent.
                long amount = 1 + random.nextLong(initialBalance * 4 / 10);
                HttpResponse<String> response = transfer(accounts.get(from), accounts.get(to), amount);
                statusCounts.computeIfAbsent(response.statusCode(), s -> new AtomicInteger()).incrementAndGet();
                if (response.statusCode() == 422) {
                    assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("INSUFFICIENT_FUNDS");
                }
            }
        });

        assertThat(statusCounts.keySet()).as("HTTP statuses seen").isSubsetOf(201, 422);
        assertThat(statusCounts.get(201)).as("some transfers succeeded").isNotNull();
        assertThat(sumOf(accounts)).as("money held by the test accounts").isEqualTo(accountCount * initialBalance);
        assertThat(completedTransfersBetween(accounts)).isEqualTo(statusCounts.get(201).get());
    }

    @Test
    void concurrentWithdrawalsNeverSpendMoreThanTheBalance() throws Exception {
        String source = fundedAccount(100_000);
        List<String> receivers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            receivers.add(fundedAccount(1));
        }
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        // 100 requests of 10,000 against 100,000: exactly 10 can succeed.
        runConcurrently(THREADS, thread -> {
            HttpResponse<String> response = transfer(source, receivers.get(thread % receivers.size()), 10_000);
            if (response.statusCode() == 201) {
                succeeded.incrementAndGet();
            } else {
                assertThat(response.statusCode()).isEqualTo(422);
                assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("INSUFFICIENT_FUNDS");
                rejected.incrementAndGet();
            }
        });

        assertThat(succeeded.get()).isEqualTo(10);
        assertThat(rejected.get()).isEqualTo(90);
        assertThat(balanceOf(source)).isZero();
    }

    @Test
    void opposingTransfersBetweenTheSamePairDoNotDeadlock() throws Exception {
        String a = fundedAccount(1_000_000);
        String b = fundedAccount(1_000_000);

        // Even threads send A→B, odd threads B→A: the classic lock-ordering deadlock if locks were taken
        // in request order instead of id order.
        runConcurrently(THREADS, thread -> {
            for (int i = 0; i < 10; i++) {
                HttpResponse<String> response = thread % 2 == 0 ? transfer(a, b, 1_000) : transfer(b, a, 1_000);
                assertThat(response.statusCode()).isEqualTo(201);
            }
        });

        // Each direction moved 50 threads × 10 × 1,000, so both end where they started.
        assertThat(balanceOf(a)).isEqualTo(1_000_000);
        assertThat(balanceOf(b)).isEqualTo(1_000_000);
    }

    @FunctionalInterface
    private interface ThreadBody {
        void run(int thread) throws Exception;
    }

    private static void runConcurrently(int threads, ThreadBody body) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                int thread = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    body.run(thread);
                    return null;
                }));
            }
            start.countDown();
        }
        for (Future<?> future : futures) {
            future.get(); // rethrows assertion failures from worker threads
        }
    }

    private String fundedAccount(long amount) throws Exception {
        HttpResponse<String> opened = post("/api/v1/accounts", null, """
                {"ownerId": "load-%s", "currency": "VND"}
                """.formatted(UUID.randomUUID()));
        String id = JsonPath.read(opened.body(), "$.id");
        HttpResponse<String> deposit = post("/api/v1/deposits", UUID.randomUUID().toString(), """
                {"accountId": "%s", "amount": %d, "currency": "VND"}
                """.formatted(id, amount));
        assertThat(deposit.statusCode()).isEqualTo(201);
        return id;
    }

    private HttpResponse<String> transfer(String from, String to, long amount) throws Exception {
        return post("/api/v1/transfers", UUID.randomUUID().toString(), """
                {"sourceAccountId": "%s", "destinationAccountId": "%s", "amount": %d, "currency": "VND"}
                """.formatted(from, to, amount));
    }

    private HttpResponse<String> post(String path, String idempotencyKey, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private long balanceOf(String id) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?::uuid", Long.class, id);
    }

    private long sumOf(List<String> ids) {
        return ids.stream().mapToLong(this::balanceOf).sum();
    }

    private int completedTransfersBetween(List<String> ids) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM transfers
                WHERE type = 'TRANSFER' AND status = 'COMPLETED' AND source_account_id::text = ANY (?)
                """, Integer.class, (Object) ids.toArray(String[]::new));
    }

    // Best effort: statistics are flushed asynchronously, so this can only under-count. Any deadlock would
    // also surface as a 503 LOCK_TIMEOUT response, which the tests above reject.
    private long deadlockCount() {
        return jdbc.queryForObject("SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()", Long.class);
    }
}
