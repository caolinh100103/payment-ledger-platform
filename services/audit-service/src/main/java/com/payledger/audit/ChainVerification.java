package com.payledger.audit;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Result of re-checking the whole audit chain.
 *
 * @param checkedEvents   records checked before the first problem (all of them if valid)
 * @param headSeq         the last record; publishing {@code headSeq} and {@code headHash} somewhere outside this
 *                        database lets a later check also detect a rewritten or truncated tail
 * @param firstInvalidSeq the first record that fails a check, or null
 * @param problem         what is wrong with it, or null
 */
public record ChainVerification(boolean valid, long checkedEvents, @Schema(nullable = true) Long headSeq,
                                @Schema(nullable = true) String headHash, @Schema(nullable = true) Long firstInvalidSeq,
                                @Schema(nullable = true) String problem) {

    /** Checks records one at a time, in {@code seq} order, and stops at the first problem. */
    static final class Walker {

        private long expectedSeq = 1;
        private String prevHash = AuditChain.GENESIS_HASH;
        private long checked;
        private Long invalidSeq;
        private String problem;

        void accept(AuditRecord record) {
            if (invalidSeq != null) {
                return;
            }
            if (record.seq() != expectedSeq) {
                fail(expectedSeq, "record " + expectedSeq + " is missing (next record is " + record.seq() + ")");
            } else if (!record.prevHash().equals(prevHash)) {
                fail(record.seq(), "prev_hash does not match the hash of record " + (record.seq() - 1));
            } else if (!record.expectedHash().equals(record.hash())) {
                fail(record.seq(), "content does not match its hash");
            } else {
                prevHash = record.hash();
                expectedSeq++;
                checked++;
            }
        }

        private void fail(long seq, String description) {
            invalidSeq = seq;
            problem = description;
        }

        ChainVerification result() {
            boolean valid = invalidSeq == null;
            Long headSeq = checked == 0 ? null : expectedSeq - 1;
            return new ChainVerification(valid, checked, valid ? headSeq : null, valid && checked > 0 ? prevHash : null,
                    invalidSeq, problem);
        }
    }
}
