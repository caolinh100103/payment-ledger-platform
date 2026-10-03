package com.payledger.security;

import com.jayway.jsonpath.JsonPath;
import com.payledger.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security events reach the outbox in the same transaction as the change, on {@code payledger.security}, for the
 * audit trail (PCI DSS 10.2.1).
 */
class SecurityEventsIntegrationTest extends ApiTestSupport {

    @Test
    void aSignUpIsRecordedAsSelfService() {
        String userId = jsonPath(signUp(uniqueUsername(), PASSWORD), "$.id");

        Event created = eventsOf(userId).getFirst();
        assertThat(created.type()).isEqualTo("com.payledger.user.created");
        assertThat(created.topic()).isEqualTo("payledger.security");
        assertThat(created.<String>read("$.actor")).isEqualTo("anonymous");
        assertThat(created.<String>read("$.data.role")).isEqualTo("CUSTOMER");
    }

    @Test
    void aStaffMemberCreatedByAnAdminIsAttributedToThatAdmin() {
        String admin = newCustomer();

        MvcTestResult created = mvc.post().uri("/api/v1/users")
                .with(asUser(admin, Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username": "%s", "password": "%s", "role": "OPERATOR"}
                        """.formatted(uniqueUsername(), PASSWORD))
                .exchange();

        Event event = eventsOf(jsonPath(created, "$.id")).getFirst();
        assertThat(event.<String>read("$.actor")).isEqualTo("user:" + admin);
        assertThat(event.<String>read("$.data.role")).isEqualTo("OPERATOR");
    }

    @Test
    void everySignInAttemptOnAUserIsRecordedWithItsOrigin() {
        String username = uniqueUsername();
        String userId = jsonPath(signUp(username, PASSWORD), "$.id");

        login(username, "not the right password");
        login(username, PASSWORD);

        List<Event> events = eventsOf(userId);
        assertThat(events).extracting(Event::type).containsExactly("com.payledger.user.created",
                "com.payledger.user.sign_in_failed", "com.payledger.user.signed_in");
        Event failed = events.get(1);
        assertThat(failed.<String>read("$.actor")).isEqualTo("anonymous");
        assertThat(failed.<String>read("$.data.reason")).isEqualTo("WRONG_PASSWORD");
        assertThat(failed.<Integer>read("$.data.failedLoginAttempts")).isEqualTo(1);
        assertThat(failed.<String>read("$.data.clientIp")).isEqualTo("127.0.0.1");
        Event signedIn = events.get(2);
        assertThat(signedIn.<String>read("$.actor")).isEqualTo("user:" + userId);
        assertThat(signedIn.payload()).doesNotContain(PASSWORD);
    }

    @Test
    void aLockoutAndTheAttemptsDuringItAreRecorded() {
        String username = uniqueUsername();
        String userId = jsonPath(signUp(username, PASSWORD), "$.id");
        for (int i = 0; i < 6; i++) {
            login(username, "wrong password " + i);
        }

        List<Event> failures = eventsOf(userId).stream()
                .filter(event -> event.type().equals("com.payledger.user.sign_in_failed")).toList();
        assertThat(failures).hasSize(6);
        assertThat(failures.get(4).<String>read("$.data.lockedUntil")).isNotNull();
        assertThat(failures.get(5).<String>read("$.data.reason")).isEqualTo("LOCKED_OUT");
    }

    @Test
    void anUnlockIsAttributedToTheOperator() {
        String userId = jsonPath(signUp(uniqueUsername(), PASSWORD), "$.id");
        String operator = newCustomer();

        mvc.post().uri("/api/v1/users/{id}/unlock", userId).with(asUser(operator, Role.OPERATOR)).exchange();

        Event unlocked = eventsOf(userId).getLast();
        assertThat(unlocked.type()).isEqualTo("com.payledger.user.unlocked");
        assertThat(unlocked.<String>read("$.actor")).isEqualTo("user:" + operator);
    }

    @Test
    void sessionsEndedBySignOutOrByTheirTheftAreRecorded() {
        String username = uniqueUsername();
        String userId = jsonPath(signUp(username, PASSWORD), "$.id");
        String signedOut = jsonPath(login(username, PASSWORD), "$.refreshToken");
        String stolen = jsonPath(login(username, PASSWORD), "$.refreshToken");

        refreshTokenCall("/api/v1/auth/logout", signedOut);
        refreshTokenCall("/api/v1/auth/refresh", stolen);
        refreshTokenCall("/api/v1/auth/refresh", stolen);

        List<Event> revoked = eventsOf(userId).stream()
                .filter(event -> event.type().equals("com.payledger.user.session_revoked")).toList();
        assertThat(revoked).extracting(event -> event.<String>read("$.data.reason"))
                .containsExactly("LOGOUT", "REFRESH_TOKEN_REUSE");
        assertThat(revoked).extracting(event -> event.<String>read("$.actor"))
                .containsExactly("user:" + userId, "system");
    }

    @Test
    void aFailedSignInForAnUnknownUsernameRecordsNothing() {
        String typo = uniqueUsername();

        login(typo, PASSWORD);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE payload::text LIKE ?", Long.class,
                "%" + typo + "%")).isZero();
    }

    @Test
    void apiKeysAreRecordedWithoutTheirSecret() {
        String admin = newCustomer();
        MvcTestResult created = mvc.post().uri("/api/v1/api-keys")
                .with(asUser(admin, Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "Partner bank", "scopes": ["deposits:write"]}
                        """)
                .exchange();
        String keyId = jsonPath(created, "$.apiKey.id");
        String key = jsonPath(created, "$.key");

        mvc.post().uri("/api/v1/api-keys/{id}/revoke", keyId).with(asUser(admin, Role.ADMIN)).exchange();
        mvc.post().uri("/api/v1/api-keys/{id}/revoke", keyId).with(asUser(admin, Role.ADMIN)).exchange();

        List<Event> events = eventsOf(keyId);
        assertThat(events).extracting(Event::type)
                .containsExactly("com.payledger.apikey.created", "com.payledger.apikey.revoked");
        assertThat(events).allSatisfy(event -> {
            assertThat(event.<String>read("$.actor")).isEqualTo("user:" + admin);
            assertThat(event.payload()).doesNotContain(key);
        });
    }

    private void refreshTokenCall(String uri, String refreshToken) {
        mvc.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken": "%s"}
                        """.formatted(refreshToken))
                .exchange();
    }

    private List<Event> eventsOf(String aggregateId) {
        return jdbc.query("""
                SELECT topic, event_type, payload::text AS payload FROM outbox
                WHERE aggregate_id = ?::uuid ORDER BY id
                """, (rs, row) -> new Event(rs.getString("topic"), rs.getString("event_type"), rs.getString("payload")),
                aggregateId);
    }

    private record Event(String topic, String type, String payload) {

        <T> T read(String path) {
            return JsonPath.read(payload, path);
        }
    }
}
