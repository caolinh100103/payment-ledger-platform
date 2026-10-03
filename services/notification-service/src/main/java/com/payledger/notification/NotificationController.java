package com.payledger.notification;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications")
class NotificationController {

    private static final int MAX_RESULTS = 100;

    private final NotificationStore store;

    NotificationController(NotificationStore store) {
        this.store = store;
    }

    /**
     * A customer's messages, newest first. Customers get their own ({@code recipientId} omitted or their own id);
     * operators name the customer, e.g. to check whether an SMS went out.
     */
    @GetMapping
    @PreAuthorize("hasRole('OPERATOR')"
            + " or (hasRole('CUSTOMER') and (#recipientId == null or #recipientId == authentication.name))")
    List<Notification> byRecipient(@RequestParam(required = false) String recipientId, Authentication authentication) {
        return store.findByRecipient(recipientId != null ? recipientId : authentication.getName(), MAX_RESULTS);
    }
}
