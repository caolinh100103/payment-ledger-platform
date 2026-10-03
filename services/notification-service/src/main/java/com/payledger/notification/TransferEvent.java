package com.payledger.notification;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * This service's own view of a transfer event (see {@code docs/events.md}). It declares only the fields it
 * uses and ignores the rest (tolerant reader), so the producer can add fields without breaking it. It is not
 * shared code: each consumer owns its reading of the contract.
 */
record TransferEvent(String specversion, UUID id, String type, String subject, Instant time, Integer schemaversion,
                     Data data) {

    static final String COMPLETED = "com.payledger.transfer.completed";
    static final String FAILED = "com.payledger.transfer.failed";
    static final int SUPPORTED_SCHEMA_VERSION = 1;

    record Data(UUID transferId, String type, String status, long amount, String currency, String description,
                Account sourceAccount, Account destinationAccount, String failureCode, String failureReason,
                UUID reversalOf) {
    }

    record Account(UUID accountId, String ownerId, String accountType, Long balanceAfter) {

        boolean isCustomer() {
            return "CUSTOMER".equals(accountType);
        }
    }

    /**
     * @throws MalformedEventException   if the message is not a transfer CloudEvent
     * @throws UnsupportedEventException if it is one, but in a schema version this service cannot read
     */
    static TransferEvent parse(String message, JsonMapper json) {
        JsonNode root;
        try {
            root = json.readTree(message);
        } catch (JacksonException e) {
            throw new MalformedEventException("Not JSON: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject() || !"1.0".equals(root.path("specversion").asString(null))
                || !root.path("id").isString() || !root.path("type").isString()) {
            throw new MalformedEventException("Missing CloudEvents attributes (specversion 1.0, id, type)");
        }
        // Checked on the envelope, before binding data: a breaking change may well not fit the records below.
        // Only the version this code was written for is accepted, because guessing could notify a wrong amount.
        // Such events go to the dead-letter topic until this service is upgraded.
        JsonNode version = root.path("schemaversion");
        if (!version.isInt() || version.intValue() != SUPPORTED_SCHEMA_VERSION) {
            throw new UnsupportedEventException("Unsupported schemaversion " + version + " for "
                    + root.path("type").asString() + "; this service reads version " + SUPPORTED_SCHEMA_VERSION);
        }

        TransferEvent event;
        try {
            event = json.readerFor(TransferEvent.class)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(root);
        } catch (JacksonException e) {
            throw new MalformedEventException("Not a transfer event: " + e.getOriginalMessage(), e);
        }
        if (event.data() == null) {
            throw new MalformedEventException("Event " + event.id() + " has no data");
        }
        return event;
    }
}
