package com.payledger.ledger;

import com.payledger.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The database is the last line of defence: these invariants must hold even if application code is buggy,
 * so they are tested with raw SQL that bypasses the domain model.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerSchemaIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Test
    void seedsOneSystemAccountPerSupportedCurrency() {
        assertThat(jdbc.queryForList("SELECT currency FROM accounts WHERE type = 'SYSTEM' ORDER BY currency", String.class))
                .containsExactly("EUR", "USD", "VND");
    }

    @Test
    void rejectsSecondSystemAccountForSameCurrency() {
        assertThatThrownBy(() -> insertAccount("SYSTEM", "VND", 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_accounts_system_currency");
    }

    @Test
    void customerBalanceCannotGoNegativeButSystemBalanceCan() {
        UUID customer = insertAccount("CUSTOMER", "VND", 0);
        UUID system = systemAccount("USD");

        assertThatThrownBy(() -> jdbc.update("UPDATE accounts SET balance = -1 WHERE id = ?", customer))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_accounts_balance_non_negative");

        // Rolled back so the system account stays at 0 for other tests.
        tx.executeWithoutResult(status -> {
            assertThat(jdbc.update("UPDATE accounts SET balance = balance - 500 WHERE id = ?", system)).isEqualTo(1);
            status.setRollbackOnly();
        });
    }

    @Test
    void balancedEntriesAreAcceptedAtCommit() {
        UUID from = insertAccount("CUSTOMER", "VND", 1_000);
        UUID to = insertAccount("CUSTOMER", "VND", 0);

        UUID transfer = tx.execute(status -> {
            UUID id = insertTransfer(from, to, 300);
            insertEntry(id, from, "DEBIT", 300);
            insertEntry(id, to, "CREDIT", 300);
            return id;
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE transfer_id = ?", Long.class, transfer))
                .isEqualTo(2);
    }

    @Test
    void unbalancedEntriesAreRejectedAtCommit() {
        UUID from = insertAccount("CUSTOMER", "VND", 1_000);
        UUID to = insertAccount("CUSTOMER", "VND", 0);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            UUID id = insertTransfer(from, to, 300);
            insertEntry(id, from, "DEBIT", 300);
            insertEntry(id, to, "CREDIT", 299);
        })).rootCause().hasMessageContaining("is unbalanced");
    }

    @Test
    void ledgerEntriesAreAppendOnly() {
        UUID from = insertAccount("CUSTOMER", "VND", 1_000);
        UUID to = insertAccount("CUSTOMER", "VND", 0);
        UUID transfer = tx.execute(status -> {
            UUID id = insertTransfer(from, to, 100);
            insertEntry(id, from, "DEBIT", 100);
            insertEntry(id, to, "CREDIT", 100);
            return id;
        });

        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount = 1 WHERE transfer_id = ?", transfer))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries WHERE transfer_id = ?", transfer))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE ledger_entries CASCADE"))
                .hasMessageContaining("append-only");
    }

    private UUID insertAccount(String type, String currency, long balance) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO accounts (id, owner_id, currency, status, balance, version, created_at, updated_at, type)
                VALUES (?, 'schema-test', ?, 'ACTIVE', ?, 0, now(), now(), ?)
                """, id, currency, balance, type);
        return id;
    }

    private UUID systemAccount(String currency) {
        return jdbc.queryForObject("SELECT id FROM accounts WHERE type = 'SYSTEM' AND currency = ?", UUID.class, currency);
    }

    private UUID insertTransfer(UUID from, UUID to, long amount) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO transfers (id, type, status, source_account_id, destination_account_id, amount, currency,
                                       created_at, updated_at)
                VALUES (?, 'TRANSFER', 'COMPLETED', ?, ?, ?, 'VND', now(), now())
                """, id, from, to, amount);
        return id;
    }

    private void insertEntry(UUID transfer, UUID account, String direction, long amount) {
        jdbc.update("""
                INSERT INTO ledger_entries (transfer_id, account_id, direction, amount, currency, balance_after, created_at)
                VALUES (?, ?, ?, ?, 'VND', 0, now())
                """, transfer, account, direction, amount);
    }
}
