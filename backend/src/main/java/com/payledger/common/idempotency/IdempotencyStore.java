package com.payledger.common.idempotency;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * SQL for the idempotency key lifecycle. All timestamps use the database clock so that every application
 * instance agrees on when a lease or a key expires.
 */
@Repository
class IdempotencyStore {

    sealed interface ClaimResult permits Claimed, Existing {
    }

    /** This request now owns the key and must execute the operation. */
    record Claimed(UUID token) implements ClaimResult {
    }

    /** Another request owns or has completed the key. */
    record Existing(String requestHash, boolean completed, StoredResponse response) implements ClaimResult {
    }

    private final JdbcTemplate jdbc;

    IdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Call in its own short transaction so other requests see the claim immediately. */
    ClaimResult claim(String scope, String key, String requestHash, Duration lease, Duration ttl) {
        // A concurrent cleanup may delete the row between our statements; a couple of attempts settle it.
        for (int attempt = 0; attempt < 3; attempt++) {
            UUID token = UUID.randomUUID();
            int inserted = jdbc.update("""
                    INSERT INTO idempotency_keys (scope, idempotency_key, request_hash, status, lock_token,
                                                  locked_until, created_at, expires_at)
                    VALUES (?, ?, ?, 'PROCESSING', ?, now() + make_interval(secs => ?), now(),
                            now() + make_interval(secs => ?))
                    ON CONFLICT (scope, idempotency_key) DO NOTHING
                    """, scope, key, requestHash, token, seconds(lease), seconds(ttl));
            if (inserted == 1) {
                return new Claimed(token);
            }

            // Take over a key whose retention expired, or whose owner died mid-request (lease expired).
            // The previous owner cannot commit afterwards: completing requires its own lock_token.
            int takenOver = jdbc.update("""
                    UPDATE idempotency_keys
                    SET request_hash = ?, status = 'PROCESSING', lock_token = ?,
                        locked_until = now() + make_interval(secs => ?),
                        response_status = NULL, response_body = NULL, response_location = NULL,
                        created_at = now(), expires_at = now() + make_interval(secs => ?)
                    WHERE scope = ? AND idempotency_key = ?
                      AND (expires_at < now()
                           OR (status = 'PROCESSING' AND locked_until < now() AND request_hash = ?))
                    """, requestHash, token, seconds(lease), seconds(ttl), scope, key, requestHash);
            if (takenOver == 1) {
                return new Claimed(token);
            }

            List<Existing> existing = jdbc.query("""
                    SELECT request_hash, status, response_status, response_body, response_location
                    FROM idempotency_keys WHERE scope = ? AND idempotency_key = ?
                    """, (rs, row) -> {
                boolean completed = "COMPLETED".equals(rs.getString("status"));
                StoredResponse response = completed
                        ? new StoredResponse(rs.getInt("response_status"), rs.getString("response_body"),
                        rs.getString("response_location"))
                        : null;
                return new Existing(rs.getString("request_hash"), completed, response);
            }, scope, key);
            if (!existing.isEmpty()) {
                return existing.getFirst();
            }
        }
        throw new IllegalStateException("Could not claim idempotency key " + key);
    }

    /**
     * Stores the response. Must run in the same transaction as the operation, so that either both the
     * operation and its recorded response commit, or neither does.
     *
     * @return false if this request no longer owns the key; the caller must then roll back
     */
    boolean complete(String scope, String key, UUID token, StoredResponse response) {
        return jdbc.update("""
                UPDATE idempotency_keys
                SET status = 'COMPLETED', response_status = ?, response_body = ?, response_location = ?,
                    locked_until = NULL
                WHERE scope = ? AND idempotency_key = ? AND status = 'PROCESSING' AND lock_token = ?
                """, response.status(), response.body(), response.location(), scope, key, token) == 1;
    }

    /** Frees the key after a failure that left no trace, so the client can retry with the same key. */
    void release(String scope, String key, UUID token) {
        jdbc.update("""
                DELETE FROM idempotency_keys
                WHERE scope = ? AND idempotency_key = ? AND status = 'PROCESSING' AND lock_token = ?
                """, scope, key, token);
    }

    int deleteExpired(int batchSize) {
        return jdbc.update("""
                DELETE FROM idempotency_keys
                WHERE ctid IN (SELECT ctid FROM idempotency_keys WHERE expires_at < now() LIMIT ?)
                """, batchSize);
    }

    private static double seconds(Duration duration) {
        return duration.toMillis() / 1000.0;
    }
}
