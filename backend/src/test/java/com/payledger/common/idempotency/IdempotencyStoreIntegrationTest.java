package com.payledger.common.idempotency;

import com.payledger.common.idempotency.IdempotencyStore.Claimed;
import com.payledger.common.idempotency.IdempotencyStore.ClaimResult;
import com.payledger.common.idempotency.IdempotencyStore.Existing;
import com.payledger.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Lease and retention edge cases that are hard to provoke through the API. */
class IdempotencyStoreIntegrationTest extends ApiTestSupport {

    private static final String SCOPE = "store-test";
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration RETENTION = Duration.ofHours(24);

    @Autowired
    IdempotencyStore store;

    @Test
    void ownerWhoseLeaseWasTakenOverCanNoLongerComplete() {
        String key = UUID.randomUUID().toString();
        Claimed original = (Claimed) store.claim(SCOPE, key, "hash-a", LEASE, RETENTION);

        // While the lease is valid, a retry sees the key as in progress.
        assertThat(store.claim(SCOPE, key, "hash-a", LEASE, RETENTION))
                .isInstanceOfSatisfying(Existing.class, e -> assertThat(e.completed()).isFalse());

        expireLease(key);
        ClaimResult takeover = store.claim(SCOPE, key, "hash-a", LEASE, RETENTION);

        assertThat(takeover).isInstanceOf(Claimed.class);
        assertThat(store.complete(SCOPE, key, original.token(), response())).isFalse();
        assertThat(store.complete(SCOPE, key, ((Claimed) takeover).token(), response())).isTrue();
    }

    @Test
    void expiredLeaseIsNotTakenOverByADifferentRequest() {
        String key = UUID.randomUUID().toString();
        store.claim(SCOPE, key, "hash-a", LEASE, RETENTION);
        expireLease(key);

        assertThat(store.claim(SCOPE, key, "hash-b", LEASE, RETENTION))
                .isInstanceOfSatisfying(Existing.class, e -> assertThat(e.requestHash()).isEqualTo("hash-a"));
    }

    @Test
    void keyPastRetentionCanBeReusedForANewRequest() {
        String key = UUID.randomUUID().toString();
        Claimed first = (Claimed) store.claim(SCOPE, key, "hash-a", LEASE, RETENTION);
        store.complete(SCOPE, key, first.token(), response());
        jdbc.update("UPDATE idempotency_keys SET expires_at = now() - interval '1 second' WHERE idempotency_key = ?", key);

        assertThat(store.claim(SCOPE, key, "hash-b", LEASE, RETENTION)).isInstanceOf(Claimed.class);
    }

    @Test
    void releaseOnlyRemovesTheCallersOwnClaim() {
        String key = UUID.randomUUID().toString();
        Claimed claimed = (Claimed) store.claim(SCOPE, key, "hash-a", LEASE, RETENTION);

        store.release(SCOPE, key, UUID.randomUUID());
        assertThat(store.claim(SCOPE, key, "hash-a", LEASE, RETENTION)).isInstanceOf(Existing.class);

        store.release(SCOPE, key, claimed.token());
        assertThat(store.claim(SCOPE, key, "hash-a", LEASE, RETENTION)).isInstanceOf(Claimed.class);
    }

    @Test
    void cleanupDeletesOnlyExpiredKeys() {
        String expired = UUID.randomUUID().toString();
        String live = UUID.randomUUID().toString();
        store.claim(SCOPE, expired, "hash", LEASE, RETENTION);
        store.claim(SCOPE, live, "hash", LEASE, RETENTION);
        jdbc.update("UPDATE idempotency_keys SET expires_at = now() - interval '1 second' WHERE idempotency_key = ?", expired);

        while (store.deleteExpired(1_000) == 1_000) {
            // drain
        }

        assertThat(jdbc.queryForList("SELECT idempotency_key FROM idempotency_keys WHERE scope = ?", String.class, SCOPE))
                .contains(live).doesNotContain(expired);
    }

    private void expireLease(String key) {
        jdbc.update("UPDATE idempotency_keys SET locked_until = now() - interval '1 second' WHERE idempotency_key = ?", key);
    }

    private static StoredResponse response() {
        return new StoredResponse(201, "{}", null);
    }
}
