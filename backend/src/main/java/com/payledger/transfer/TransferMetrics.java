package com.payledger.transfer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

/**
 * Business metrics and the log line of every money movement, recorded only <em>after its transaction commits</em>.
 * A movement whose transaction rolls back (a lock timeout, a lost idempotency lease) never happened, so it is neither
 * counted nor logged as if it had.
 *
 * <ul>
 *   <li>{@code payledger_transfers_total{type, status, failure_code}}: movements by outcome. FAILED ones are business
 *       rejections such as INSUFFICIENT_FUNDS: normal, but a sudden rise is worth a look.</li>
 *   <li>{@code payledger_transfers_amount_total{type, currency}}: money moved by COMPLETED movements, in the minor unit
 *       of the currency: the payment volume. For dashboards only; the ledger is the record of money.</li>
 *   <li>{@code payledger_transfer_duration_seconds{type, status}}: from the start of the movement to its commit,
 *       including the wait for account locks. A histogram, so p50, p95 and p99 can be computed across instances.</li>
 * </ul>
 *
 * <p>Tags have a fixed, small set of values (types, statuses, failure codes, currencies). Account or user ids are
 * never tags: every distinct value creates a time series in Prometheus.
 *
 * <p>Every outcome is registered at zero on startup. A counter that only appears at its first increment shows up in
 * Prometheus already at 1, and {@code increase()} sees no change: the first rejection of a kind would be invisible,
 * and an alert on "any increase" would never fire for a single event.
 */
@Component
class TransferMetrics {

    private static final Logger log = LoggerFactory.getLogger(TransferMetrics.class);
    private static final String NO_FAILURE = "none";

    private final MeterRegistry meters;

    TransferMetrics(MeterRegistry meters) {
        this.meters = meters;
        for (TransferType type : TransferType.values()) {
            transfers(type.name(), TransferStatus.COMPLETED.name(), NO_FAILURE);
            for (String code : TransferRules.FAILURE_CODES) {
                transfers(type.name(), TransferStatus.FAILED.name(), code);
            }
        }
    }

    /** Must be called inside the transaction that records {@code transfer}, once its outcome is decided. */
    void onCommit(Transfer transfer, long startNanos) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                record(transfer, Duration.ofNanos(System.nanoTime() - startNanos));
            }
        });
    }

    private void record(Transfer transfer, Duration duration) {
        String type = transfer.getType().name();
        String status = transfer.getStatus().name();
        String failureCode = transfer.getFailureCode() == null ? NO_FAILURE : transfer.getFailureCode();

        transfers(type, status, failureCode).increment();
        if (transfer.getStatus() == TransferStatus.COMPLETED) {
            Counter.builder("payledger.transfers.amount")
                    .description("Money moved by completed movements, in the minor unit of the currency")
                    .tags("type", type, "currency", transfer.getCurrency())
                    .register(meters)
                    .increment(transfer.getAmount());
        }
        Timer.builder("payledger.transfer.duration")
                .description("Time from the start of a money movement to its commit, lock waits included")
                .tags("type", type, "status", status)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(10))
                .register(meters)
                .record(duration);

        // Ids and outcome only: amounts, descriptions and owners are in the ledger and the audit trail, not in logs.
        LoggingEventBuilder line = log.atInfo()
                .setMessage("{} {} {}")
                .addArgument(type)
                .addArgument(transfer.getId())
                .addArgument(transfer.getFailureCode() == null ? status : status + " " + transfer.getFailureCode())
                .addKeyValue("transfer.id", transfer.getId())
                .addKeyValue("transfer.type", type)
                .addKeyValue("transfer.status", status)
                // ECS: the duration of the event in nanoseconds.
                .addKeyValue("event.duration", duration.toNanos());
        if (transfer.getFailureCode() != null) {
            line = line.addKeyValue("transfer.failure_code", transfer.getFailureCode());
        }
        line.log();
    }

    private Counter transfers(String type, String status, String failureCode) {
        return Counter.builder("payledger.transfers")
                .description("Money movements recorded, by outcome")
                .tags("type", type, "status", status, "failure.code", failureCode)
                .register(meters);
    }
}
