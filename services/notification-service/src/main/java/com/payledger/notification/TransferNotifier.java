package com.payledger.notification;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * Renders, records and sends the notifications for one event, at most once per event id.
 *
 * <p>The "processed" marker and the notification records are written in one transaction, and a send that throws
 * rolls both back, so the event is retried as a whole. What this cannot make exactly-once is the external send
 * itself: if the process dies after the gateway accepted a message but before the commit, the retry sends it
 * again. A gateway that accepts a client reference (the notification id) can drop that repeat; otherwise a rare
 * duplicate message is the accepted cost, as it is for every at-least-once notification system.
 *
 * <p>Metrics: {@code payledger_events_consumed_total{outcome}} counts events handled ({@code processed}) and
 * redeliveries skipped ({@code duplicate}) once their transaction commits. {@code payledger_notifications_sent_total}
 * counts messages as they are handed to the gateway, since a send cannot be rolled back.
 */
@Service
class TransferNotifier {

    static final String CONSUMER = "notification-service";

    private static final Logger log = LoggerFactory.getLogger(TransferNotifier.class);

    private final ProcessedEvents processed;
    private final NotificationStore store;
    private final NotificationSender sender;
    private final MeterRegistry meters;

    TransferNotifier(ProcessedEvents processed, NotificationStore store, NotificationSender sender,
                     MeterRegistry meters) {
        this.processed = processed;
        this.store = store;
        this.sender = sender;
        this.meters = meters;
    }

    /** @return the notifications sent; empty for a redelivered event or one that concerns no customer */
    @Transactional
    public List<Notification> handle(TransferEvent event) {
        if (!processed.markProcessed(CONSUMER, event.id())) {
            log.info("Skipped redelivered event {} ({})", event.id(), event.type());
            countAfterCommit("duplicate");
            return List.of();
        }
        List<Notification> notifications = BalanceChangeMessages.render(event);
        for (Notification notification : notifications) {
            store.save(notification);
            sender.send(notification);
            meters.counter("payledger.notifications.sent", "channel", notification.channel().name()).increment();
        }
        countAfterCommit("processed");
        return notifications;
    }

    private void countAfterCommit(String outcome) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                meters.counter("payledger.events.consumed", "outcome", outcome).increment();
            }
        });
    }
}
