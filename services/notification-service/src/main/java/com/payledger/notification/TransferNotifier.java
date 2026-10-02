package com.payledger.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Renders, records and sends the notifications for one event, at most once per event id.
 *
 * <p>The "processed" marker and the notification records are written in one transaction, and a send that throws
 * rolls both back, so the event is retried as a whole. What this cannot make exactly-once is the external send
 * itself: if the process dies after the gateway accepted a message but before the commit, the retry sends it
 * again. A gateway that accepts a client reference (the notification id) can drop that repeat; otherwise a rare
 * duplicate message is the accepted cost, as it is for every at-least-once notification system.
 */
@Service
class TransferNotifier {

    static final String CONSUMER = "notification-service";

    private static final Logger log = LoggerFactory.getLogger(TransferNotifier.class);

    private final ProcessedEvents processed;
    private final NotificationStore store;
    private final NotificationSender sender;

    TransferNotifier(ProcessedEvents processed, NotificationStore store, NotificationSender sender) {
        this.processed = processed;
        this.store = store;
        this.sender = sender;
    }

    /** @return the notifications sent; empty for a redelivered event or one that concerns no customer */
    @Transactional
    public List<Notification> handle(TransferEvent event) {
        if (!processed.markProcessed(CONSUMER, event.id())) {
            log.info("Skipped redelivered event {} ({})", event.id(), event.type());
            return List.of();
        }
        List<Notification> notifications = BalanceChangeMessages.render(event);
        for (Notification notification : notifications) {
            store.save(notification);
            sender.send(notification);
        }
        return notifications;
    }
}
