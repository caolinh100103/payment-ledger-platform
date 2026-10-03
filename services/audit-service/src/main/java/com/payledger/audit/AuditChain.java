package com.payledger.audit;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * The hash that links each audit record to the one before it:
 * {@code hash(n) = SHA-256(fields of record n, hash(n-1))}. Changing any field of any record changes its hash,
 * which no longer matches the {@code prev_hash} stored in the next record.
 *
 * <p>Every field is length-prefixed before hashing, so no two different records can produce the same input
 * (with a plain separator, {@code "a|b" + "c"} and {@code "a" + "b|c"} would collide).
 */
final class AuditChain {

    /** The {@code prev_hash} of the first record. */
    static final String GENESIS_HASH = "0".repeat(64);

    private AuditChain() {
    }

    static String hash(long seq, AuditableEvent event, Instant recordedAt, String prevHash) {
        MessageDigest digest = sha256();
        field(digest, Long.toString(seq));
        field(digest, event.eventId().toString());
        field(digest, event.actor());
        field(digest, event.action());
        field(digest, event.resourceId());
        field(digest, event.occurredAt().toString());
        field(digest, event.source());
        field(digest, event.payload());
        field(digest, recordedAt.toString());
        field(digest, prevHash);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void field(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
