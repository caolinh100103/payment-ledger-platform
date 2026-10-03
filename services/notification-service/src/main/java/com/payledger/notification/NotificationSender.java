package com.payledger.notification;

/**
 * Hands a message to a delivery channel (an SMS gateway, an email provider, a push service). Throwing makes the
 * event be retried later, so an implementation should throw only for failures that may pass (timeouts, 5xx).
 */
interface NotificationSender {

    void send(Notification notification);
}
