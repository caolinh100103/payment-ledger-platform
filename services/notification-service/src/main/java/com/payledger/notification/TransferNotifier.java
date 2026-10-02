package com.payledger.notification;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Renders, records and sends the notifications for one event. The records are written in one transaction, and
 * a send that throws rolls them back, so the event is retried as a whole.
 */
@Service
class TransferNotifier {

    private final NotificationStore store;
    private final NotificationSender sender;

    TransferNotifier(NotificationStore store, NotificationSender sender) {
        this.store = store;
        this.sender = sender;
    }

    @Transactional
    public List<Notification> handle(TransferEvent event) {
        List<Notification> notifications = BalanceChangeMessages.render(event);
        for (Notification notification : notifications) {
            store.save(notification);
            sender.send(notification);
        }
        return notifications;
    }
}
