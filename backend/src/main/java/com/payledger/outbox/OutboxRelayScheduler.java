package com.payledger.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the outbox. Every instance runs it; {@link OutboxRelay} makes that safe. */
@Component
@ConditionalOnProperty(name = "payledger.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelay relay;

    OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${payledger.outbox.relay.poll-interval:PT0.2S}")
    void relay() {
        try {
            // Drain while there is work (each batch only takes the oldest pending event per aggregate), then
            // sleep until the next poll.
            while (relay.publishBatch() > 0 && !Thread.currentThread().isInterrupted()) {
                // keep draining
            }
        } catch (RuntimeException e) {
            // e.g. the database is unreachable; the next poll tries again
            log.warn("Outbox relay run failed: {}", e.toString());
        }
    }
}
