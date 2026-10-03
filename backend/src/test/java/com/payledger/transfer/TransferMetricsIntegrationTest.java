package com.payledger.transfer;

import com.payledger.support.ApiTestSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The business metrics: what they count, when, and that Prometheus gets histograms for the percentiles. */
class TransferMetricsIntegrationTest extends ApiTestSupport {

    @Autowired
    MeterRegistry meters;

    @Autowired
    TransferMetrics transferMetrics;

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    DataSource dataSource;

    @Test
    void countsCompletedTransfersAndTheMoneyTheyMoved() {
        String alice = fundedAccount("VND", 1_000_000);
        String bob = openAccount("VND");
        double completed = count("payledger.transfers", "type", "TRANSFER", "status", "COMPLETED", "failure.code", "none");
        double volume = count("payledger.transfers.amount", "type", "TRANSFER", "currency", "VND");
        long timed = timerCount("type", "TRANSFER", "status", "COMPLETED");

        assertThat(transfer(alice, bob, 250_000, "VND")).hasStatus(HttpStatus.CREATED);

        assertThat(count("payledger.transfers", "type", "TRANSFER", "status", "COMPLETED", "failure.code", "none"))
                .isEqualTo(completed + 1);
        assertThat(count("payledger.transfers.amount", "type", "TRANSFER", "currency", "VND"))
                .isEqualTo(volume + 250_000);
        assertThat(timerCount("type", "TRANSFER", "status", "COMPLETED")).isEqualTo(timed + 1);
    }

    @Test
    void countsRejectedTransfersByFailureCodeWithoutAddingToTheVolume() {
        String alice = fundedAccount("USD", 1_000);
        String bob = openAccount("USD");
        double rejected = count("payledger.transfers", "type", "TRANSFER", "status", "FAILED",
                "failure.code", "INSUFFICIENT_FUNDS");
        double volume = count("payledger.transfers.amount", "type", "TRANSFER", "currency", "USD");

        assertThat(transfer(alice, bob, 5_000, "USD")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);

        assertThat(count("payledger.transfers", "type", "TRANSFER", "status", "FAILED",
                "failure.code", "INSUFFICIENT_FUNDS")).isEqualTo(rejected + 1);
        assertThat(count("payledger.transfers.amount", "type", "TRANSFER", "currency", "USD")).isEqualTo(volume);
    }

    @Test
    void registersEveryOutcomeAtZeroSoTheFirstOneShowsAsAnIncrease() {
        for (TransferType type : TransferType.values()) {
            assertThat(meters.find("payledger.transfers").tags("type", type.name(), "status", "FAILED",
                    "failure.code", "CURRENCY_MISMATCH").counter()).isNotNull();
        }
        assertThat(meters.find("payledger.lock.failures").tag("reason", "deadlock").counter()).isNotNull();
        assertThat(meters.find("payledger.rate.limit.requests").tags("policy", "api-key", "outcome", "bypassed")
                .counter()).isNotNull();
        assertThat(meters.find("payledger.outbox.publish.attempts").tags("topic", "payledger.security",
                "outcome", "failed").counter()).isNotNull();
    }

    @Test
    void doesNotCountAMovementWhoseTransactionRolledBack() {
        Transfer transfer = Transfer.transfer(UUID.randomUUID(), UUID.randomUUID(), 1_000, "EUR", null, "user:test");
        transfer.complete();
        double completed = count("payledger.transfers", "type", "TRANSFER", "status", "COMPLETED", "failure.code", "none");

        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            transferMetrics.onCommit(transfer, System.nanoTime());
            status.setRollbackOnly();
        });

        assertThat(count("payledger.transfers", "type", "TRANSFER", "status", "COMPLETED", "failure.code", "none"))
                .isEqualTo(completed);
    }

    @Test
    void countsReplayedIdempotencyKeysPerRoute() {
        String alice = fundedAccount("VND", 100_000);
        String body = transferBody(alice, openAccount("VND"), 1_000, "VND");
        String key = UUID.randomUUID().toString();
        double executed = count("payledger.idempotency.requests", "uri", "/api/v1/transfers", "outcome", "executed");
        double replayed = count("payledger.idempotency.requests", "uri", "/api/v1/transfers", "outcome", "replayed");
        double reused = count("payledger.idempotency.requests", "uri", "/api/v1/transfers", "outcome", "key_reused");

        postWithKey("/api/v1/transfers", key, body, asOwnerOf(alice));
        postWithKey("/api/v1/transfers", key, body, asOwnerOf(alice));
        postWithKey("/api/v1/transfers", key, body.replace("\"amount\": 1000", "\"amount\": 2000"), asOwnerOf(alice));

        assertThat(count("payledger.idempotency.requests", "uri", "/api/v1/transfers", "outcome", "executed"))
                .isEqualTo(executed + 1);
        assertThat(count("payledger.idempotency.requests", "uri", "/api/v1/transfers", "outcome", "replayed"))
                .isEqualTo(replayed + 1);
        assertThat(count("payledger.idempotency.requests", "uri", "/api/v1/transfers", "outcome", "key_reused"))
                .isEqualTo(reused + 1);
    }

    @Test
    void countsLockTimeoutsAndTimesLockWaits() throws Exception {
        String alice = fundedAccount("VND", 100_000);
        String bob = openAccount("VND");
        double timeouts = count("payledger.lock.failures", "reason", "lock_timeout");
        long waits = meters.get("payledger.account.lock.wait").timer().count();

        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement("SELECT 1 FROM accounts WHERE id = ?::uuid FOR UPDATE")) {
                lock.setString(1, bob);
                lock.executeQuery();
            }
            assertThat(transfer(alice, bob, 1_000, "VND")).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
            other.rollback();
        }

        assertThat(count("payledger.lock.failures", "reason", "lock_timeout")).isEqualTo(timeouts + 1);
        assertThat(count("payledger.lock.failures", "reason", "deadlock")).isZero();
        // A timed-out wait is a failure, not a lock wait.
        assertThat(meters.get("payledger.account.lock.wait").timer().count()).isEqualTo(waits);

        assertThat(transfer(alice, bob, 1_000, "VND")).hasStatus(HttpStatus.CREATED);
        assertThat(meters.get("payledger.account.lock.wait").timer().count()).isEqualTo(waits + 1);
    }

    @Test
    void exposesHistogramsSoPercentilesCanBeAggregatedAcrossInstances() {
        String alice = fundedAccount("VND", 100_000);
        transfer(alice, openAccount("VND"), 1_000, "VND");

        assertThat(mvc.get().uri("/actuator/prometheus")).hasStatusOk().bodyText()
                .contains("payledger_transfers_total{application=\"payledger-core\",failure_code=\"none\",status=\"COMPLETED\",type=\"TRANSFER\"}")
                .contains("payledger_transfers_amount_total{")
                .contains("payledger_transfer_duration_seconds_bucket{")
                .contains("payledger_account_lock_wait_seconds_bucket{")
                .contains("payledger_idempotency_requests_total{")
                .contains("http_server_requests_seconds_bucket{");
    }

    private double count(String name, String... tags) {
        Counter counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private long timerCount(String... tags) {
        Timer timer = meters.find("payledger.transfer.duration").tags(tags).timer();
        return timer == null ? 0 : timer.count();
    }
}
