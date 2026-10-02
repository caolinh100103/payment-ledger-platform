package com.payledger.notification;

import java.util.UUID;

/** CloudEvents shaped like the ones the core service publishes (docs/events.md). */
final class TestEvents {

    static final String ALICE_ACCOUNT = "3f2a9c1e-0d1b-4c55-9b3e-7a2f8e4d1c00";
    static final String BOB_ACCOUNT = "7d4b1e2f-6a5c-4e3d-8f2a-1b0c9d8e7f6a";
    static final String SYSTEM_ACCOUNT = "00000000-0000-4000-8000-000000000001";
    /** 15:15 in Vietnam (UTC+7). */
    static final String TIME = "2026-10-03T08:15:30.123456Z";

    private TestEvents() {
    }

    static String completedTransfer(UUID eventId, String description) {
        return event(eventId, "com.payledger.transfer.completed", 1, data("TRANSFER", "COMPLETED", 250_000, "VND",
                description, account(ALICE_ACCOUNT, "alice", "CUSTOMER", 750_000L),
                account(BOB_ACCOUNT, "bob", "CUSTOMER", 250_000L), null));
    }

    static String event(UUID eventId, String type, int schemaVersion, String data) {
        return """
                {"specversion":"1.0","id":"%s","source":"/payledger/core","type":"%s","subject":"%s","time":"%s",\
                "datacontenttype":"application/json","schemaversion":%d,"actor":"anonymous","data":%s}"""
                .formatted(eventId, type, UUID.randomUUID(), TIME, schemaVersion, data);
    }

    static String data(String type, String status, long amount, String currency, String description, String source,
                       String destination, String failureCode) {
        return """
                {"transferId":"%s","type":"%s","status":"%s","amount":%d,"currency":"%s","description":%s,\
                "sourceAccount":%s,"destinationAccount":%s,"failureCode":%s,"failureReason":null,\
                "reversalOf":null,"reversedBy":null,"createdAt":"%s"}"""
                .formatted(UUID.randomUUID(), type, status, amount, currency, quoted(description), source, destination,
                        quoted(failureCode), TIME);
    }

    static String account(String accountId, String ownerId, String type, Long balanceAfter) {
        return """
                {"accountId":"%s","ownerId":"%s","accountType":"%s","balanceAfter":%s}"""
                .formatted(accountId, ownerId, type, balanceAfter);
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
