package com.payledger.account;

import com.jayway.jsonpath.JsonPath;
import com.payledger.security.Role;
import com.payledger.support.ApiTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Account lifecycle changes reach the outbox on {@code payledger.accounts}, attributed to whoever made them. */
class AccountEventsIntegrationTest extends ApiTestSupport {

    @Test
    void eachStatusChangeIsRecordedWithWhoMadeIt() {
        String customer = newCustomer();
        String operator = newCustomer();
        String account = openAccount(customer, "VND");

        mvc.post().uri("/api/v1/accounts/{id}/freeze", account).with(asUser(operator, Role.OPERATOR)).exchange();
        mvc.post().uri("/api/v1/accounts/{id}/unfreeze", account).with(asUser(operator, Role.OPERATOR)).exchange();
        mvc.post().uri("/api/v1/accounts/{id}/close", account).with(asCustomer(customer)).exchange();

        List<Event> events = eventsOf(account);
        assertThat(events).extracting(Event::type).containsExactly("com.payledger.account.opened",
                "com.payledger.account.frozen", "com.payledger.account.unfrozen", "com.payledger.account.closed");
        assertThat(events).extracting(event -> event.<String>read("$.actor")).containsExactly(
                "user:" + customer, "user:" + operator, "user:" + operator, "user:" + customer);
        assertThat(events).extracting(event -> event.<String>read("$.data.status"))
                .containsExactly("ACTIVE", "FROZEN", "ACTIVE", "CLOSED");
        assertThat(events).allSatisfy(event -> {
            assertThat(event.topic()).isEqualTo("payledger.accounts");
            assertThat(event.<String>read("$.subject")).isEqualTo(account);
            assertThat(event.<String>read("$.data.ownerId")).isEqualTo(customer);
        });
    }

    @Test
    void aRejectedChangeRecordsNothing() {
        String account = openAccount("VND");

        mvc.post().uri("/api/v1/accounts/{id}/unfreeze", account).with(asOperator()).exchange();

        assertThat(eventsOf(account)).extracting(Event::type).containsExactly("com.payledger.account.opened");
    }

    private List<Event> eventsOf(String accountId) {
        return jdbc.query("""
                SELECT topic, event_type, payload::text AS payload FROM outbox
                WHERE aggregate_type = 'account' AND aggregate_id = ?::uuid ORDER BY id
                """, (rs, row) -> new Event(rs.getString("topic"), rs.getString("event_type"), rs.getString("payload")),
                accountId);
    }

    private record Event(String topic, String type, String payload) {

        <T> T read(String path) {
            return JsonPath.read(payload, path);
        }
    }
}
