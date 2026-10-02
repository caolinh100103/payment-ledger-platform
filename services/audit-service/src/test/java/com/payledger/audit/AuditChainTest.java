package com.payledger.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuditChainTest {

    private static final Instant NOW = Instant.parse("2026-10-03T08:15:30.123456Z");
    private static final UUID ID = UUID.fromString("5c0d6f6e-3b8e-4b9a-9d4f-2f1a7c3e8b10");

    @Test
    void isDeterministicHexSha256() {
        String hash = AuditChain.hash(1, event("alice", "t-1"), NOW, AuditChain.GENESIS_HASH);

        assertThat(hash).matches("[0-9a-f]{64}")
                .isEqualTo(AuditChain.hash(1, event("alice", "t-1"), NOW, AuditChain.GENESIS_HASH));
    }

    @Test
    void anyChangedFieldChangesTheHash() {
        String hash = AuditChain.hash(1, event("alice", "t-1"), NOW, AuditChain.GENESIS_HASH);

        assertThat(AuditChain.hash(2, event("alice", "t-1"), NOW, AuditChain.GENESIS_HASH)).isNotEqualTo(hash);
        assertThat(AuditChain.hash(1, event("mallory", "t-1"), NOW, AuditChain.GENESIS_HASH)).isNotEqualTo(hash);
        assertThat(AuditChain.hash(1, event("alice", "t-1"), NOW.plusNanos(1_000), AuditChain.GENESIS_HASH))
                .isNotEqualTo(hash);
        assertThat(AuditChain.hash(1, event("alice", "t-1"), NOW, "f".repeat(64))).isNotEqualTo(hash);
    }

    @Test
    void movingCharactersBetweenFieldsChangesTheHash() {
        // With a plain separator these two would hash the same input: "ab|c" vs "a|bc".
        assertThat(AuditChain.hash(1, event("ab", "c"), NOW, AuditChain.GENESIS_HASH))
                .isNotEqualTo(AuditChain.hash(1, event("a", "bc"), NOW, AuditChain.GENESIS_HASH));
    }

    @Test
    void missingResourceIsNotTheSameAsAnEmptyOne() {
        assertThat(AuditChain.hash(1, event("alice", null), NOW, AuditChain.GENESIS_HASH))
                .isNotEqualTo(AuditChain.hash(1, event("alice", ""), NOW, AuditChain.GENESIS_HASH));
    }

    private static AuditableEvent event(String actor, String resourceId) {
        return new AuditableEvent(ID, actor, "com.payledger.transfer.completed", resourceId, NOW, "/payledger/core",
                "{}");
    }
}
