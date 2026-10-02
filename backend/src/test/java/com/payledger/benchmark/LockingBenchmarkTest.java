package com.payledger.benchmark;

import com.payledger.TestcontainersConfiguration;
import com.payledger.account.Account;
import com.payledger.account.AccountLocker;
import com.payledger.account.AccountRepository;
import com.payledger.account.AccountService;
import com.payledger.common.error.ResourceNotFoundException;
import com.payledger.support.LedgerInvariants;
import com.payledger.transfer.TransferService;
import com.payledger.transfer.TransferStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pessimistic (SELECT ... FOR NO KEY UPDATE, the production strategy) versus optimistic (@Version check at
 * commit, retry on conflict) locking for transfers, at three contention levels.
 *
 * <p>Not part of the normal build. Run with:
 * <pre>./mvnw test -Dtest=LockingBenchmarkTest -Dbenchmark=true</pre>
 * Results are printed and written to {@code target/benchmark/locking.md}.
 */
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
@SpringBootTest
@Import({TestcontainersConfiguration.class, LockingBenchmarkTest.Config.class})
class LockingBenchmarkTest {

    private static final int THREADS = 32;
    private static final int TRANSFERS_PER_THREAD = 100;
    private static final int MAX_OPTIMISTIC_ATTEMPTS = 20;

    enum Strategy { PESSIMISTIC, OPTIMISTIC }

    @Autowired
    SwitchableAccountLocker locker;

    @Autowired
    TransferService transfers;

    @Autowired
    AccountService accountService;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void compareStrategies() throws Exception {
        List<String> rows = new ArrayList<>();

        // Warm up JIT, connection pool and caches so the first measured run is not penalised.
        run("warm-up", Strategy.PESSIMISTIC, uniformPairs(50));
        run("warm-up", Strategy.OPTIMISTIC, uniformPairs(50));

        for (Strategy strategy : Strategy.values()) {
            rows.add(run("Low: 1,000 accounts, random pairs", strategy, uniformPairs(1_000)).row());
        }
        for (Strategy strategy : Strategy.values()) {
            rows.add(run("Medium: 20 accounts, random pairs", strategy, uniformPairs(20)).row());
        }
        for (Strategy strategy : Strategy.values()) {
            rows.add(run("High: 32 payers → 1 merchant", strategy, hotMerchant()).row());
        }

        String table = """
                | Contention | Strategy | Throughput (tx/s) | p50 (ms) | p95 (ms) | p99 (ms) | Retries | Gave up |
                |---|---|---:|---:|---:|---:|---:|---:|
                """ + String.join("\n", rows) + "\n";
        System.out.println("\n" + table);
        Path out = Path.of("target", "benchmark", "locking.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, table);

        LedgerInvariants.assertHold(jdbc);
    }

    /** Builds, per thread, the source and destination for each transfer. */
    private interface Workload {
        IntFunction<UUID[]> pairsFor(int thread);
    }

    private Workload uniformPairs(int accountCount) {
        List<UUID> ids = fundedAccounts(accountCount);
        return thread -> i -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int from = random.nextInt(accountCount);
            int to = (from + 1 + random.nextInt(accountCount - 1)) % accountCount;
            return new UUID[]{ids.get(from), ids.get(to)};
        };
    }

    private Workload hotMerchant() {
        List<UUID> payers = fundedAccounts(THREADS);
        UUID merchant = fundedAccounts(1).getFirst();
        return thread -> i -> new UUID[]{payers.get(thread), merchant};
    }

    private Result run(String scenario, Strategy strategy, Workload workload) throws Exception {
        locker.useStrategy(strategy);
        AtomicLong retries = new AtomicLong();
        AtomicLong gaveUp = new AtomicLong();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        long began;
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int t = 0; t < THREADS; t++) {
                IntFunction<UUID[]> pairs = workload.pairsFor(t);
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < TRANSFERS_PER_THREAD; i++) {
                        UUID[] pair = pairs.apply(i);
                        long t0 = System.nanoTime();
                        int attempts = transferWithRetry(strategy, pair[0], pair[1]);
                        if (attempts > MAX_OPTIMISTIC_ATTEMPTS) {
                            gaveUp.incrementAndGet();
                        } else {
                            latencies.add(System.nanoTime() - t0);
                        }
                        retries.addAndGet(Math.min(attempts, MAX_OPTIMISTIC_ATTEMPTS) - 1);
                    }
                    return null;
                }));
            }
            began = System.nanoTime();
            start.countDown();
        }
        long elapsed = System.nanoTime() - began;
        for (Future<?> future : futures) {
            future.get();
        }
        return new Result(scenario, strategy, latencies, elapsed, retries.get(), gaveUp.get());
    }

    /** @return attempts used, or MAX + 1 if the transfer never committed */
    private int transferWithRetry(Strategy strategy, UUID from, UUID to) throws InterruptedException {
        for (int attempt = 1; attempt <= MAX_OPTIMISTIC_ATTEMPTS; attempt++) {
            try {
                var transfer = transfers.transfer(from, to, 1, "VND", null);
                assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
                return attempt;
            } catch (ConcurrencyFailureException conflict) {
                if (strategy == Strategy.PESSIMISTIC) {
                    throw conflict; // a lock timeout would be a finding, not something to hide
                }
                // Exponential backoff with full jitter, capped at ~32 ms.
                long cap = 1L << Math.min(attempt, 5);
                Thread.sleep(ThreadLocalRandom.current().nextLong(cap + 1));
            }
        }
        return MAX_OPTIMISTIC_ATTEMPTS + 1;
    }

    private List<UUID> fundedAccounts(int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID id = accountService.open("bench-" + UUID.randomUUID(), "VND").getId();
            transfers.deposit(id, 1_000_000_000L, "VND", null);
            ids.add(id);
        }
        return ids;
    }

    private record Result(String scenario, Strategy strategy, List<Long> latenciesNanos, long elapsedNanos,
                          long retries, long gaveUp) {

        String row() {
            List<Long> sorted = new ArrayList<>(latenciesNanos);
            Collections.sort(sorted);
            double seconds = elapsedNanos / 1e9;
            return "| %s | %s | %.0f | %.1f | %.1f | %.1f | %d | %d |".formatted(scenario, strategy,
                    sorted.size() / seconds, percentile(sorted, 50), percentile(sorted, 95), percentile(sorted, 99),
                    retries, gaveUp);
        }

        private static double percentile(List<Long> sorted, int p) {
            if (sorted.isEmpty()) {
                return Double.NaN;
            }
            int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
            return sorted.get(Math.max(index, 0)) / 1e6;
        }
    }

    /**
     * In OPTIMISTIC mode, reads both accounts without a row lock; Hibernate's {@code @Version} check on the
     * balance UPDATE at commit detects a concurrent change and the caller retries. Accounts are still read
     * in id order so the UPDATEs at commit are issued in a consistent order and cannot deadlock.
     */
    static class SwitchableAccountLocker extends AccountLocker {

        private final AccountRepository accounts;
        private volatile Strategy strategy = Strategy.PESSIMISTIC;

        SwitchableAccountLocker(AccountRepository accounts, EntityManager entityManager) {
            super(accounts, entityManager, Duration.ofSeconds(3));
            this.accounts = accounts;
        }

        // A method, not a field write: the bean is a CGLIB proxy (because of @Transactional), and only method
        // calls reach the target instance.
        public void useStrategy(Strategy strategy) {
            this.strategy = strategy;
        }

        @Override
        public LockedPair lock(UUID sourceId, UUID destinationId) {
            if (strategy == Strategy.PESSIMISTIC) {
                return super.lock(sourceId, destinationId);
            }
            boolean sourceFirst = sourceId.compareTo(destinationId) < 0;
            Account first = read(sourceFirst ? sourceId : destinationId);
            Account second = read(sourceFirst ? destinationId : sourceId);
            return sourceFirst ? new LockedPair(first, second) : new LockedPair(second, first);
        }

        private Account read(UUID id) {
            return accounts.findById(id).orElseThrow(() -> new ResourceNotFoundException("Account", id));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {

        @Bean
        @Primary
        SwitchableAccountLocker switchableAccountLocker(AccountRepository accounts, EntityManager entityManager) {
            return new SwitchableAccountLocker(accounts, entityManager);
        }
    }
}
