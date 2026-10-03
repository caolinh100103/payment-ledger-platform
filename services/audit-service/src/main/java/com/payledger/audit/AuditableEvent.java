package com.payledger.audit;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * The parts of a CloudEvent the audit log indexes, plus the event itself, verbatim. The audit service does not
 * interpret {@code data}, so it records events of any type and any {@code schemaversion}.
 *
 * @param action     the CloudEvents {@code type}, e.g. {@code com.payledger.transfer.completed}
 * @param resourceId the CloudEvents {@code subject}, e.g. the transfer id
 * @param payload    the message exactly as received
 */
public record AuditableEvent(UUID eventId, String actor, String action, String resourceId, Instant occurredAt,
                             String source, String payload) {

    static final String UNKNOWN_ACTOR = "unknown";

    static AuditableEvent parse(String message, JsonMapper json) {
        JsonNode root;
        try {
            root = json.readTree(message);
        } catch (JacksonException e) {
            throw new MalformedEventException("Not JSON: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new MalformedEventException("Not a JSON object");
        }
        if (!"1.0".equals(optionalString(root, "specversion"))) {
            throw new MalformedEventException("Unsupported CloudEvents specversion: " + optionalString(root, "specversion"));
        }
        try {
            String actor = optionalString(root, "actor");
            return new AuditableEvent(
                    UUID.fromString(requiredString(root, "id")),
                    actor == null || actor.isBlank() ? UNKNOWN_ACTOR : actor,
                    requiredString(root, "type"),
                    optionalString(root, "subject"),
                    // PostgreSQL keeps microseconds; truncating first keeps the hashed value equal to the stored one.
                    OffsetDateTime.parse(requiredString(root, "time")).toInstant().truncatedTo(ChronoUnit.MICROS),
                    requiredString(root, "source"),
                    message);
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new MalformedEventException("Invalid CloudEvent attribute: " + e.getMessage(), e);
        }
    }

    private static String requiredString(JsonNode root, String name) {
        String value = optionalString(root, name);
        if (value == null || value.isBlank()) {
            throw new MalformedEventException("Missing required CloudEvent attribute '" + name + "'");
        }
        return value;
    }

    private static String optionalString(JsonNode root, String name) {
        JsonNode node = root.get(name);
        return node != null && node.isString() ? node.stringValue() : null;
    }
}
