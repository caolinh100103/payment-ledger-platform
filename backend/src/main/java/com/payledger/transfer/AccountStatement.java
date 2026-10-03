package com.payledger.transfer;

import com.payledger.ledger.EntryDirection;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * An account's statement: its ledger entries, newest first, each with the movement it belongs to and the balance it
 * left, as on a bank statement. Read from the append-only ledger, so it always adds up to the balance.
 *
 * <p>Paged with a cursor, as Stripe lists are: {@code startingAfter} is the last entry id the client has, and
 * {@code idx_ledger_entries_account (account_id, id)} serves every page with one index range scan, however deep.
 * Page numbers ({@code OFFSET}) would get slower with each page and skip or repeat entries posted meanwhile.
 */
@Repository
class AccountStatement {

    private static final RowMapper<StatementEntry> ROW = (rs, row) -> new StatementEntry(
            rs.getLong("id"),
            rs.getObject("transfer_id", UUID.class),
            TransferType.valueOf(rs.getString("type")),
            TransferStatus.valueOf(rs.getString("status")),
            EntryDirection.valueOf(rs.getString("direction")),
            rs.getLong("amount"),
            rs.getString("currency"),
            rs.getLong("balance_after"),
            rs.getObject("counterparty_account_id", UUID.class),
            rs.getString("description"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    AccountStatement(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @param startingAfter the id of the last entry already seen; null for the newest page */
    @Transactional(readOnly = true)
    StatementPage page(UUID accountId, Long startingAfter, int limit) {
        // One row more than asked tells whether there is a next page, without a COUNT.
        List<StatementEntry> entries = jdbc.query("""
                        SELECT e.id, e.transfer_id, t.type, t.status, e.direction, e.amount, e.currency,
                               e.balance_after,
                               CASE e.direction WHEN 'DEBIT' THEN t.destination_account_id
                                                ELSE t.source_account_id END AS counterparty_account_id,
                               t.description, e.created_at
                        FROM ledger_entries e
                        JOIN transfers t ON t.id = e.transfer_id
                        WHERE e.account_id = ? AND e.id < ?
                        ORDER BY e.id DESC
                        LIMIT ?
                        """, ROW, accountId, startingAfter == null ? Long.MAX_VALUE : startingAfter, limit + 1);
        boolean hasMore = entries.size() > limit;
        return new StatementPage(hasMore ? entries.subList(0, limit) : entries, hasMore);
    }

    /**
     * @param transferStatus        COMPLETED, or REVERSED once a reversal has moved the money back
     * @param counterpartyAccountId the other account of the movement; for a deposit, the funding SYSTEM account
     */
    record StatementEntry(
            @Schema(description = "Entry id; pass the last one as startingAfter for the next page") long id,
            UUID transferId,
            TransferType type,
            TransferStatus transferStatus,
            @Schema(description = "DEBIT takes money out of the account, CREDIT puts it in") EntryDirection direction,
            long amount,
            String currency,
            @Schema(description = "The account's balance right after this entry") long balanceAfter,
            UUID counterpartyAccountId,
            @Schema(nullable = true) String description,
            Instant createdAt) {
    }

    /** A page of a list, as Stripe returns them: the items, and whether more follow. */
    record StatementPage(List<StatementEntry> data, boolean hasMore) {
    }
}
