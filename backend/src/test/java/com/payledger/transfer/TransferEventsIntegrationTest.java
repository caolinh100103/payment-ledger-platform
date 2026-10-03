package com.payledger.transfer;

import com.jayway.jsonpath.JsonPath;
import com.payledger.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Every recorded transfer leaves its lifecycle events in the outbox, written by the same transaction. */
class TransferEventsIntegrationTest extends ApiTestSupport {

    private static final String CREATED = "com.payledger.transfer.created";
    private static final String COMPLETED = "com.payledger.transfer.completed";
    private static final String FAILED = "com.payledger.transfer.failed";
    private static final String REVERSED = "com.payledger.transfer.reversed";

    @Test
    void completedTransferEmitsCreatedThenCompletedWithBalancesAfter() {
        String alice = openAccount("alice", "VND");
        deposit(alice, 1_000_000, "VND");
        String bob = openAccount("bob", "VND");

        String transferId = jsonPath(transfer(alice, bob, 250_000, "VND"), "$.id");

        List<Event> events = eventsOf(transferId);
        assertThat(events).extracting(Event::type).containsExactly(CREATED, COMPLETED);

        Event created = events.get(0);
        assertThat(created.<String>read("$.id")).isEqualTo(created.id());
        assertThat(created.<String>read("$.type")).isEqualTo(CREATED);
        assertThat(created.<String>read("$.specversion")).isEqualTo("1.0");
        assertThat(created.<String>read("$.source")).isEqualTo("/payledger/core");
        assertThat(created.<String>read("$.subject")).isEqualTo(transferId);
        assertThat(created.<Integer>read("$.schemaversion")).isEqualTo(1);
        assertThat(created.<String>read("$.actor")).isEqualTo("anonymous");
        assertThat(created.<String>read("$.data.status")).isEqualTo("PENDING");
        assertThat(created.<Object>read("$.data.sourceAccount.balanceAfter")).isNull();

        Event completed = events.get(1);
        assertThat(completed.<String>read("$.data.transferId")).isEqualTo(transferId);
        assertThat(completed.<String>read("$.data.type")).isEqualTo("TRANSFER");
        assertThat(completed.<String>read("$.data.status")).isEqualTo("COMPLETED");
        assertThat(completed.<Integer>read("$.data.amount")).isEqualTo(250_000);
        assertThat(completed.<String>read("$.data.currency")).isEqualTo("VND");
        assertThat(completed.<String>read("$.data.description")).isEqualTo("Tiền nhà tháng 10");
        assertThat(completed.<String>read("$.data.sourceAccount.accountId")).isEqualTo(alice);
        assertThat(completed.<String>read("$.data.sourceAccount.ownerId")).isEqualTo("alice");
        assertThat(completed.<String>read("$.data.sourceAccount.accountType")).isEqualTo("CUSTOMER");
        assertThat(completed.<Integer>read("$.data.sourceAccount.balanceAfter")).isEqualTo(750_000);
        assertThat(completed.<String>read("$.data.destinationAccount.ownerId")).isEqualTo("bob");
        assertThat(completed.<Integer>read("$.data.destinationAccount.balanceAfter")).isEqualTo(250_000);
        assertThat(completed.id()).isNotEqualTo(created.id());
    }

    @Test
    void depositIsFundedBySystemAccount() {
        String alice = openAccount("VND");

        String depositId = jsonPath(deposit(alice, 500_000, "VND"), "$.id");

        Event completed = eventsOf(depositId).getLast();
        assertThat(completed.type()).isEqualTo(COMPLETED);
        assertThat(completed.<String>read("$.data.type")).isEqualTo("DEPOSIT");
        assertThat(completed.<String>read("$.data.sourceAccount.accountType")).isEqualTo("SYSTEM");
        assertThat(completed.<Integer>read("$.data.destinationAccount.balanceAfter")).isEqualTo(500_000);
    }

    @Test
    void rejectedTransferEmitsCreatedThenFailedWithTheCode() {
        String alice = fundedAccount("VND", 100_000);
        String bob = openAccount("VND");

        MvcTestResult result = transfer(alice, bob, 100_001, "VND");

        String transferId = jsonPath(result, "$.transferId");
        List<Event> events = eventsOf(transferId);
        assertThat(events).extracting(Event::type).containsExactly(CREATED, FAILED);
        Event failed = events.get(1);
        assertThat(failed.<String>read("$.data.status")).isEqualTo("FAILED");
        assertThat(failed.<String>read("$.data.failureCode")).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(failed.<Object>read("$.data.sourceAccount.balanceAfter")).isNull();
    }

    @Test
    void reversalEmitsItsOwnLifecycleAndMarksTheOriginalReversed() {
        String alice = fundedAccount("VND", 1_000_000);
        String bob = openAccount("VND");
        String originalId = jsonPath(transfer(alice, bob, 300_000, "VND"), "$.id");

        String reversalId = jsonPath(reverse(originalId), "$.id");

        List<Event> reversalEvents = eventsOf(reversalId);
        assertThat(reversalEvents).extracting(Event::type).containsExactly(CREATED, COMPLETED);
        Event reversalCompleted = reversalEvents.get(1);
        assertThat(reversalCompleted.<String>read("$.data.type")).isEqualTo("REVERSAL");
        assertThat(reversalCompleted.<String>read("$.data.reversalOf")).isEqualTo(originalId);
        assertThat(reversalCompleted.<String>read("$.data.sourceAccount.accountId")).isEqualTo(bob);
        assertThat(reversalCompleted.<Integer>read("$.data.sourceAccount.balanceAfter")).isZero();
        assertThat(reversalCompleted.<Integer>read("$.data.destinationAccount.balanceAfter")).isEqualTo(1_000_000);

        List<Event> originalEvents = eventsOf(originalId);
        assertThat(originalEvents).extracting(Event::type).containsExactly(CREATED, COMPLETED, REVERSED);
        Event reversed = originalEvents.get(2);
        assertThat(reversed.<String>read("$.subject")).isEqualTo(originalId);
        assertThat(reversed.<String>read("$.data.status")).isEqualTo("REVERSED");
        assertThat(reversed.<String>read("$.data.reversedBy")).isEqualTo(reversalId);
        assertThat(reversed.<String>read("$.data.sourceAccount.accountId")).isEqualTo(alice);
        assertThat(reversed.<String>read("$.data.destinationAccount.accountId")).isEqualTo(bob);
    }

    @Test
    void failedReversalLeavesTheOriginalWithoutAReversedEvent() {
        String alice = fundedAccount("VND", 500_000);
        String bob = openAccount("VND");
        String carol = openAccount("VND");
        String originalId = jsonPath(transfer(alice, bob, 500_000, "VND"), "$.id");
        transfer(bob, carol, 500_000, "VND");

        MvcTestResult result = reverse(originalId);

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(eventsOf(jsonPath(result, "$.transferId"))).extracting(Event::type).containsExactly(CREATED, FAILED);
        assertThat(eventsOf(originalId)).extracting(Event::type).containsExactly(CREATED, COMPLETED);
    }

    @Test
    void requestRejectedBeforeATransferExistsEmitsNothing() {
        String alice = fundedAccount("VND", 100_000);
        long before = outboxSize();

        MvcTestResult result = transfer(alice, UUID.randomUUID().toString(), 1_000, "VND");

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(outboxSize()).isEqualTo(before);
    }

    @Test
    void idempotentReplayEmitsNoNewEvents() {
        String alice = fundedAccount("VND", 100_000);
        String bob = openAccount("VND");
        String key = UUID.randomUUID().toString();
        String body = transferBody(alice, bob, 10_000, "VND");

        String transferId = jsonPath(postWithKey("/api/v1/transfers", key, body), "$.id");
        postWithKey("/api/v1/transfers", key, body);

        assertThat(eventsOf(transferId)).hasSize(2);
    }

    private MvcTestResult reverse(String transferId) {
        return postWithKey("/api/v1/transfers/" + transferId + "/reversals", UUID.randomUUID().toString(), """
                {"reason": "Customer dispute"}
                """);
    }

    private List<Event> eventsOf(String aggregateId) {
        return jdbc.query("""
                SELECT event_id, event_type, payload::text AS payload FROM outbox
                WHERE aggregate_type = 'transfer' AND aggregate_id = ?::uuid ORDER BY id
                """, (rs, row) -> new Event(rs.getString("event_id"), rs.getString("event_type"),
                rs.getString("payload")), aggregateId);
    }

    private long outboxSize() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox", Long.class);
    }

    private record Event(String id, String type, String payload) {

        <T> T read(String path) {
            return JsonPath.read(payload, path);
        }
    }
}
