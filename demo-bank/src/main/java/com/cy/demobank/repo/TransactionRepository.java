package com.cy.demobank.repo;

import com.cy.demobank.domain.Txn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Reads/writes the {@code transactions} ledger (SQLite, via JdbcTemplate — no JPA). Every money
 * action records a row here so the bank can show a statement and an activity feed.
 */
@Repository
public class TransactionRepository {

    private final JdbcTemplate jdbc;

    public TransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Txn> MAPPER = (rs, n) -> new Txn(
            rs.getString("id"),
            rs.getString("account_id"),
            rs.getString("owner_user"),
            rs.getString("kind"),
            rs.getString("counterparty_name"),
            rs.getString("counterparty_ref"),
            rs.getString("reference"),
            rs.getLong("amount_cents"),
            rs.getString("rail"),
            rs.getString("verdict"),
            rs.getString("friction"),
            rs.getString("reason_code"),
            rs.getString("scam_pattern"),
            rs.getInt("applied") == 1,
            rs.getLong("created_epoch_ms"));

    public void insert(Txn t) {
        jdbc.update("""
                INSERT INTO transactions
                  (id, account_id, owner_user, kind, counterparty_name, counterparty_ref, reference,
                   amount_cents, rail, verdict, friction, reason_code, scam_pattern, applied, created_epoch_ms)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                t.id(), t.accountId(), t.ownerUser(), t.kind(), t.counterpartyName(), t.counterpartyRef(),
                t.reference(), t.amountCents(), t.rail(), t.verdict(), t.friction(), t.reasonCode(),
                t.scamPattern(), t.applied() ? 1 : 0, t.createdEpochMs());
    }

    /** Statement for one account, newest first. */
    public List<Txn> findByAccount(String accountId) {
        return jdbc.query("SELECT * FROM transactions WHERE account_id = ? ORDER BY created_epoch_ms DESC",
                MAPPER, accountId);
    }

    /** Activity for a customer across all their accounts, newest first. */
    public List<Txn> findByOwner(String ownerUser) {
        return jdbc.query("SELECT * FROM transactions WHERE owner_user = ? ORDER BY created_epoch_ms DESC",
                MAPPER, ownerUser);
    }

    /** The most recent {@code limit} actions for a customer (dashboard preview). */
    public List<Txn> recentByOwner(String ownerUser, int limit) {
        return jdbc.query("SELECT * FROM transactions WHERE owner_user = ? ORDER BY created_epoch_ms DESC LIMIT ?",
                MAPPER, ownerUser, limit);
    }
}
