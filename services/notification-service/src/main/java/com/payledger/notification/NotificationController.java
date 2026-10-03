package com.payledger.notification;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Temporary: any caller can read any customer's messages until Phase 4 limits it to the customer themselves.
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

    private static final int MAX_RESULTS = 100;

    private final NotificationStore store;

    NotificationController(NotificationStore store) {
        this.store = store;
    }

    /** A customer's messages, newest first. */
    @GetMapping
    List<Notification> byRecipient(@RequestParam String recipientId) {
        return store.findByRecipient(recipientId, MAX_RESULTS);
    }
}
