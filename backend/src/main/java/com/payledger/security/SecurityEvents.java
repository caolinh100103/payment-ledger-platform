package com.payledger.security;

import com.payledger.outbox.CloudEvent;
import com.payledger.outbox.EventTopics;
import com.payledger.outbox.Outbox;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Records security events in the outbox, inside the transaction that makes the change, for the audit trail. The list
 * follows PCI DSS requirement 10.2.1: sign-ins and invalid access attempts (10.2.1.4), creation of users and changes
 * to credentials and privileges (10.2.1.5), and actions of administrators (10.2.1.2).
 *
 * <p>A failed sign-in for a username that does not exist is not recorded: there is no user to attach it to, and the
 * text typed may be a password entered in the wrong field. The rate limit per address covers that case.
 */
@Component
public class SecurityEvents {

    public static final String USER_CREATED = "com.payledger.user.created";
    public static final String SIGNED_IN = "com.payledger.user.signed_in";
    public static final String SIGN_IN_FAILED = "com.payledger.user.sign_in_failed";
    public static final String UNLOCKED = "com.payledger.user.unlocked";
    public static final String SESSION_REVOKED = "com.payledger.user.session_revoked";
    public static final String API_KEY_CREATED = "com.payledger.apikey.created";
    public static final String API_KEY_REVOKED = "com.payledger.apikey.revoked";

    private static final String SOURCE = "/payledger/core";
    private static final int SCHEMA_VERSION = 1;

    private final Outbox outbox;

    SecurityEvents(Outbox outbox) {
        this.outbox = outbox;
    }

    /** Keyed and subjected by the user id, so a user's security history is one ordered trail. */
    public void user(String type, UserData data, String actor) {
        outbox.append(EventTopics.SECURITY, "user", data.userId(),
                CloudEvent.of(SOURCE, type, SCHEMA_VERSION, data.userId().toString(), actor, data));
    }

    public void apiKey(String type, ApiKeyData data, String actor) {
        outbox.append(EventTopics.SECURITY, "apikey", data.apiKeyId(),
                CloudEvent.of(SOURCE, type, SCHEMA_VERSION, data.apiKeyId().toString(), actor, data));
    }

    /**
     * {@code data} of the {@code com.payledger.user.*} events, schema version 1. Fields that do not apply are null.
     *
     * @param clientIp            where a sign-in came from (PCI DSS 10.2.2: origination of the event)
     * @param failedLoginAttempts on {@code sign_in_failed}: consecutive failures so far
     * @param lockedUntil         on {@code sign_in_failed}: set once the user is locked out
     * @param reason              on {@code sign_in_failed}: {@code WRONG_PASSWORD} or {@code LOCKED_OUT};
     *                            on {@code session_revoked}: {@code LOGOUT} or {@code REFRESH_TOKEN_REUSE}
     */
    public record UserData(UUID userId, String username, Role role, String clientIp, Integer failedLoginAttempts,
                           Instant lockedUntil, UUID sessionId, String reason) {

        public static UserData of(UUID userId, String username, Role role) {
            return new UserData(userId, username, role, null, null, null, null, null);
        }
    }

    /** {@code data} of the {@code com.payledger.apikey.*} events, schema version 1. Never the key itself. */
    public record ApiKeyData(UUID apiKeyId, String name, String prefix, Set<String> scopes, Instant expiresAt) {
    }
}
