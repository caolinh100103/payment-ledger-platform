package com.payledger.security.apikey;

import com.payledger.common.error.ResourceNotFoundException;
import com.payledger.security.Actor;
import com.payledger.security.Secrets;
import com.payledger.security.SecurityEvents;
import com.payledger.security.SecurityEvents.ApiKeyData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Issues, lists, revokes and checks API keys. A key is shown once, when it is created; only its SHA-256 is stored,
 * like GitHub and Stripe do. Losing a key means revoking it and creating a new one.
 */
@Service
public class ApiKeys {

    private static final RowMapper<ApiKey> API_KEY = (rs, row) -> new ApiKey(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("prefix"),
            parseScopes(rs.getString("scopes")),
            rs.getString("created_by"),
            instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("expires_at")),
            instant(rs.getTimestamp("revoked_at")),
            instant(rs.getTimestamp("last_used_at")));

    private final JdbcTemplate jdbc;
    private final SecurityEvents events;

    ApiKeys(JdbcTemplate jdbc, SecurityEvents events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    /** A key as listed: everything but the secret. */
    public record ApiKey(UUID id, String name, String prefix, Set<ApiKeyScope> scopes, String createdBy,
                         Instant createdAt, Instant expiresAt, Instant revokedAt, Instant lastUsedAt) {
    }

    /** @param key the full key, available only here */
    public record CreatedApiKey(ApiKey apiKey, String key) {
    }

    @Transactional
    public CreatedApiKey create(String name, Set<ApiKeyScope> scopes, Instant expiresAt, Actor createdBy) {
        String key = ApiKeyFormat.generate();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO api_keys (id, name, prefix, key_hash, scopes, created_by, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, now(), ?)
                """, id, name, ApiKeyFormat.displayPrefix(key), Secrets.sha256(key), formatScopes(scopes),
                createdBy.name(), expiresAt == null ? null : Timestamp.from(expiresAt));
        ApiKey created = get(id);
        events.apiKey(SecurityEvents.API_KEY_CREATED, data(created), createdBy.name());
        return new CreatedApiKey(created, key);
    }

    @Transactional(readOnly = true)
    public List<ApiKey> list() {
        return jdbc.query("SELECT * FROM api_keys ORDER BY created_at DESC", API_KEY);
    }

    @Transactional(readOnly = true)
    public ApiKey get(UUID id) {
        return jdbc.query("SELECT * FROM api_keys WHERE id = ?", API_KEY, id).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("API key", id));
    }

    /** Takes effect on the next request; revoking twice keeps the first revocation time. */
    @Transactional
    public ApiKey revoke(UUID id, Actor revokedBy) {
        int revoked = jdbc.update("UPDATE api_keys SET revoked_at = now() WHERE id = ? AND revoked_at IS NULL", id);
        ApiKey apiKey = get(id);
        if (revoked == 1) {
            events.apiKey(SecurityEvents.API_KEY_REVOKED, data(apiKey), revokedBy.name());
        }
        return apiKey;
    }

    /** Empty for a malformed, unknown, revoked or expired key. */
    @Transactional
    public Optional<ApiKeyAuthentication> authenticate(String key) {
        if (!ApiKeyFormat.isWellFormed(key)) {
            return Optional.empty();
        }
        Optional<ApiKeyAuthentication> found = jdbc.query("""
                SELECT id, name, scopes FROM api_keys
                WHERE key_hash = ? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now())
                """, (rs, row) -> new ApiKeyAuthentication(rs.getObject("id", UUID.class), rs.getString("name"),
                parseScopes(rs.getString("scopes"))), Secrets.sha256(key)).stream().findFirst();
        found.ifPresent(authentication -> jdbc.update("""
                UPDATE api_keys SET last_used_at = now()
                WHERE id = ? AND (last_used_at IS NULL OR last_used_at < now() - interval '1 minute')
                """, authentication.getKeyId()));
        return found;
    }

    private static ApiKeyData data(ApiKey apiKey) {
        return new ApiKeyData(apiKey.id(), apiKey.name(), apiKey.prefix(),
                apiKey.scopes().stream().map(ApiKeyScope::value).collect(Collectors.toSet()), apiKey.expiresAt());
    }

    private static String formatScopes(Set<ApiKeyScope> scopes) {
        return scopes.stream().map(ApiKeyScope::value).sorted().collect(Collectors.joining(" "));
    }

    private static Set<ApiKeyScope> parseScopes(String scopes) {
        return Arrays.stream(scopes.split(" ")).map(ApiKeyScope::of).collect(Collectors.toUnmodifiableSet());
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
