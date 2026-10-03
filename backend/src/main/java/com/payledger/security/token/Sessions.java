package com.payledger.security.token;

import com.payledger.security.Secrets;
import com.payledger.security.SecurityProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Sign-in sessions and their rotating refresh tokens (RFC 9700 §4.14.2).
 *
 * <p>Every refresh exchanges the presented token for a new one. Only the newest token of a session works, so a
 * token that leaks is only useful until its owner refreshes next. If an already exchanged token comes back, either
 * the attacker or the real client is replaying a copy, and there is no telling which: the whole session is revoked,
 * as Auth0 and Okta do with reuse detection. Both parties then have to sign in again, and only one of them can.
 *
 * <p>All timestamps use the database clock, so every instance agrees on when a token or session expires.
 */
@Repository
public class Sessions {

    private static final int TOKEN_BYTES = 32;

    public sealed interface RefreshOutcome permits Refreshed, Invalid, Reused {
    }

    public record Refreshed(UUID userId, UUID sessionId, IssuedRefreshToken refreshToken) implements RefreshOutcome {
    }

    /** Unknown, expired, or belonging to a session that ended. */
    public record Invalid() implements RefreshOutcome {
    }

    /** Presented after it had been exchanged; the session has just been revoked. */
    public record Reused(UUID sessionId) implements RefreshOutcome {
    }

    public record IssuedRefreshToken(String value, Duration expiresIn) {
    }

    private final JdbcTemplate jdbc;
    private final Duration tokenTtl;
    private final Duration sessionTtl;

    Sessions(JdbcTemplate jdbc, SecurityProperties properties) {
        this.jdbc = jdbc;
        this.tokenTtl = properties.refresh().tokenTtl();
        this.sessionTtl = properties.refresh().sessionTtl();
    }

    /** Starts a session for a user who has just signed in. */
    @Transactional
    public Started start(UUID userId) {
        UUID sessionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO auth_sessions (id, user_id, created_at, expires_at)
                VALUES (?, ?, now(), now() + make_interval(secs => ?))
                """, sessionId, userId, seconds(sessionTtl));
        return new Started(sessionId, issue(sessionId));
    }

    public record Started(UUID sessionId, IssuedRefreshToken refreshToken) {
    }

    /**
     * Never throws for a bad token: a detected reuse must commit its revocation, so the outcome is returned and the
     * caller turns it into a 401 afterwards.
     */
    @Transactional
    public RefreshOutcome refresh(String token) {
        String hash = Secrets.sha256(token);
        // Atomic: of two requests racing with the same token, the second waits for the first's row lock, then finds
        // used_at set and matches nothing. A token can therefore be exchanged only once.
        List<UUID> exchanged = jdbc.queryForList("""
                UPDATE refresh_tokens SET used_at = now()
                WHERE token_hash = ? AND used_at IS NULL AND expires_at > now()
                RETURNING session_id
                """, UUID.class, hash);
        if (exchanged.isEmpty()) {
            return reuseOrInvalid(hash);
        }
        UUID sessionId = exchanged.getFirst();
        List<UUID> user = jdbc.queryForList("""
                SELECT user_id FROM auth_sessions WHERE id = ? AND revoked_at IS NULL AND expires_at > now()
                """, UUID.class, sessionId);
        if (user.isEmpty()) {
            return new Invalid();
        }
        return new Refreshed(user.getFirst(), sessionId, issue(sessionId));
    }

    /** Ends the session of {@code token}, whatever state the token is in (RFC 7009: no error for a bad token). */
    @Transactional
    public void revoke(String token) {
        jdbc.update("""
                UPDATE auth_sessions SET revoked_at = now(), revoke_reason = 'LOGOUT'
                WHERE revoked_at IS NULL AND id = (SELECT session_id FROM refresh_tokens WHERE token_hash = ?)
                """, Secrets.sha256(token));
    }

    /** Deletes sessions past their absolute lifetime, with their tokens. Small batches keep each DELETE short. */
    int deleteExpired(int batchSize) {
        return jdbc.update("""
                DELETE FROM auth_sessions
                WHERE ctid IN (SELECT ctid FROM auth_sessions WHERE expires_at < now() LIMIT ?)
                """, batchSize);
    }

    private RefreshOutcome reuseOrInvalid(String hash) {
        List<UUID> used = jdbc.queryForList("""
                SELECT session_id FROM refresh_tokens WHERE token_hash = ? AND used_at IS NOT NULL
                """, UUID.class, hash);
        if (used.isEmpty()) {
            return new Invalid();
        }
        UUID sessionId = used.getFirst();
        jdbc.update("""
                UPDATE auth_sessions SET revoked_at = now(), revoke_reason = 'REFRESH_TOKEN_REUSE'
                WHERE id = ? AND revoked_at IS NULL
                """, sessionId);
        return new Reused(sessionId);
    }

    // The new token never outlives its session.
    private IssuedRefreshToken issue(UUID sessionId) {
        String token = Secrets.random(TOKEN_BYTES);
        Long expiresInSeconds = jdbc.queryForObject("""
                INSERT INTO refresh_tokens (token_hash, session_id, issued_at, expires_at)
                SELECT ?, id, now(), LEAST(now() + make_interval(secs => ?), expires_at)
                FROM auth_sessions WHERE id = ?
                RETURNING CEIL(EXTRACT(EPOCH FROM expires_at - now()))::BIGINT
                """, Long.class, Secrets.sha256(token), seconds(tokenTtl), sessionId);
        return new IssuedRefreshToken(token, Duration.ofSeconds(expiresInSeconds));
    }

    private static double seconds(Duration duration) {
        return duration.toMillis() / 1000.0;
    }
}
