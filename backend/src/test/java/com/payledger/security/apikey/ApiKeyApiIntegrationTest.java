package com.payledger.security.apikey;

import com.payledger.security.Role;
import com.payledger.security.Secrets;
import com.payledger.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyApiIntegrationTest extends ApiTestSupport {

    @Test
    void anAdminCreatesAKeyThatIsShownOnceAndStoredAsAHash() {
        MvcTestResult created = createKey("Vietcombank top-up webhook");

        assertThat(created).hasStatus(HttpStatus.CREATED);
        assertThat(created).headers().containsHeader("Location");
        String key = jsonPath(created, "$.key");
        String id = jsonPath(created, "$.apiKey.id");
        assertThat(key).startsWith("plk_");
        assertThat(created).bodyJson().extractingPath("$.apiKey.prefix").isEqualTo(key.substring(0, 12));
        assertThat(created).bodyJson().extractingPath("$.apiKey.scopes").asArray().containsExactly("deposits:write");

        MvcTestResult listed = mvc.get().uri("/api/v1/api-keys").header("Authorization", bearer(Role.ADMIN)).exchange();
        assertThat(bodyOf(listed)).contains(id).doesNotContain(key);
        assertThat(jdbc.queryForObject("SELECT key_hash FROM api_keys WHERE id = ?::uuid", String.class, id))
                .isEqualTo(Secrets.sha256(key));
    }

    @Test
    void aKeyAuthenticatesAMachineClient() {
        String key = jsonPath(createKey("Partner bank"), "$.key");
        String account = openAccount("VND");

        MvcTestResult deposit = mvc.post().uri("/api/v1/deposits")
                .header("X-API-Key", key)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"accountId": "%s", "amount": 500000, "currency": "VND"}
                        """.formatted(account))
                .exchange();

        assertThat(deposit).hasStatus(HttpStatus.CREATED);
        // Authenticated, but a machine has no user profile and no role.
        assertThat(withKey(key, "/api/v1/users/me"))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson().extractingPath("$.code").isEqualTo("ACCESS_DENIED");
    }

    @Test
    void aRevokedKeyStopsWorkingImmediately() {
        MvcTestResult created = createKey("Old integration");
        String key = jsonPath(created, "$.key");

        assertThat(mvc.post().uri("/api/v1/api-keys/{id}/revoke", (String) jsonPath(created, "$.apiKey.id"))
                .header("Authorization", bearer(Role.ADMIN)))
                .hasStatusOk()
                .bodyJson().extractingPath("$.revokedAt").isNotNull();

        assertThat(withKey(key, "/api/v1/users/me"))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_API_KEY");
    }

    @Test
    void anExpiredKeyIsRejected() {
        MvcTestResult created = createKey("Temporary integration");
        jdbc.update("UPDATE api_keys SET expires_at = now() - interval '1 second' WHERE id = ?::uuid",
                (String) jsonPath(created, "$.apiKey.id"));

        assertThat(withKey(jsonPath(created, "$.key"), "/api/v1/users/me")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unknownAndMalformedKeysAreRejected() {
        assertThat(withKey(ApiKeyFormat.generate(), "/api/v1/users/me"))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_API_KEY");
        assertThat(withKey("plk_definitely-not-a-key", "/api/v1/users/me")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void recordsWhenAKeyWasLastUsed() {
        MvcTestResult created = createKey("Monitored integration");
        String id = jsonPath(created, "$.apiKey.id");
        assertThat(created).bodyJson().extractingPath("$.apiKey.lastUsedAt").isNull();

        withKey(jsonPath(created, "$.key"), "/api/v1/users/me");

        assertThat(mvc.get().uri("/api/v1/api-keys/{id}", id).header("Authorization", bearer(Role.ADMIN)))
                .bodyJson().extractingPath("$.lastUsedAt").isNotNull();
    }

    @Test
    void onlyAnAdminManagesKeys() {
        assertThat(mvc.post().uri("/api/v1/api-keys")
                .header("Authorization", bearer(Role.OPERATOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "Sneaky", "scopes": ["deposits:write"]}
                        """))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri("/api/v1/api-keys").header("Authorization", bearer(Role.CUSTOMER)))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void rejectsUnknownScopes() {
        assertThat(mvc.post().uri("/api/v1/api-keys")
                .header("Authorization", bearer(Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "Greedy", "scopes": ["everything:write"]}
                        """))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    private MvcTestResult createKey(String name) {
        return mvc.post().uri("/api/v1/api-keys")
                .header("Authorization", bearer(Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s", "scopes": ["deposits:write"]}
                        """.formatted(name))
                .exchange();
    }

    private MvcTestResult withKey(String key, String uri) {
        return mvc.get().uri(uri).header("X-API-Key", key).exchange();
    }
}
