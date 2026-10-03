package com.payledger.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Simulated delivery: writes the message to the log instead of calling an SMS gateway. */
@Component
class LogNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LogNotificationSender.class);

    @Override
    public void send(Notification notification) {
        log.info("{} to {}: {}", notification.channel(), notification.recipientId(), notification.message());
    }
}
