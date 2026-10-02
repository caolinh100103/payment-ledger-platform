package com.payledger.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The accounting invariants of the whole ledger, checked with plain SQL. These are the queries a daily
 * reconciliation job would run.
 */
public final class LedgerInvariants {

    private LedgerInvariants() {
    }

    public static void assertHold(JdbcTemplate jdbc) {
        // 1. Double entry: across the whole ledger, debits equal credits.
        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0)  AS debit,
                       COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0) AS credit
                FROM ledger_entries
                """);
        assertThat(totals.get("debit")).as("total DEBIT = total CREDIT").isEqualTo(totals.get("credit"));

        // 2. ... and per transfer.
        assertThat(jdbc.queryForList("""
                SELECT transfer_id FROM ledger_entries GROUP BY transfer_id
                HAVING SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END) <> 0
                """)).as("unbalanced transfers").isEmpty();

        // 3. Money is neither created nor destroyed: every currency nets to zero.
        assertThat(jdbc.queryForList("SELECT currency FROM accounts GROUP BY currency HAVING SUM(balance) <> 0"))
                .as("currencies whose balances do not sum to 0").isEmpty();

        // 4. No customer is overdrawn.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE type = 'CUSTOMER' AND balance < 0", Long.class))
                .as("overdrawn customer accounts").isZero();

        // 5. Every stored balance can be re-derived from the ledger.
        List<Map<String, Object>> drift = jdbc.queryForList("""
                SELECT a.id, a.balance,
                       COALESCE(SUM(CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE -e.amount END), 0) AS derived
                FROM accounts a LEFT JOIN ledger_entries e ON e.account_id = a.id
                GROUP BY a.id, a.balance
                HAVING a.balance <> COALESCE(SUM(CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE -e.amount END), 0)
                """);
        assertThat(drift).as("accounts whose balance differs from their ledger").isEmpty();

        // 6. The running balance on the latest entry matches the account (statement consistency).
        assertThat(jdbc.queryForList("""
                SELECT a.id FROM accounts a
                JOIN LATERAL (SELECT balance_after FROM ledger_entries e
                              WHERE e.account_id = a.id ORDER BY e.id DESC LIMIT 1) last ON true
                WHERE last.balance_after <> a.balance
                """)).as("accounts whose last balance_after differs from the balance").isEmpty();

        // 7. Only COMPLETED or REVERSED transfers have entries, FAILED ones have none.
        assertThat(jdbc.queryForList("""
                SELECT t.id FROM transfers t JOIN ledger_entries e ON e.transfer_id = t.id
                WHERE t.status NOT IN ('COMPLETED', 'REVERSED')
                """)).as("non-posted transfers with entries").isEmpty();
    }
}
