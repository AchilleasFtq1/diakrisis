package com.cy.demobank.repo;

import com.cy.demobank.domain.Txn;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

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
            rs.getString("event_id"),
            rs.getString("status_override"),
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
                  (id, event_id, status_override, account_id, owner_user, kind, counterparty_name,
                   counterparty_ref, reference, amount_cents, rail, verdict, friction, reason_code,
                   scam_pattern, applied, created_epoch_ms)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                t.id(), t.eventId(), t.statusOverride(), t.accountId(), t.ownerUser(), t.kind(),
                t.counterpartyName(), t.counterpartyRef(), t.reference(), t.amountCents(), t.rail(),
                t.verdict(), t.friction(), t.reasonCode(), t.scamPattern(), t.applied() ? 1 : 0,
                t.createdEpochMs());
    }

    /**
     * Set the customer-confirmed/cancelled status on a <em>pending</em> transaction (by its decision
     * event id). This is a guarded, single-row state transition: the {@code status_override IS NULL}
     * predicate blocks re-marking an already-resolved row, and the affected-row assertion surfaces both
     * the not-found case and any unexpected multi-row case (event_id is not unique by schema, so two
     * rows could in principle share it). Without the guard a re-submitted confirm/cancel would flip
     * {@code applied} for every matching row, corrupting the ledger.
     *
     * @throws IllegalStateException if no single pending transaction matched the event id.
     */
    public void markStatus(String eventId, String status) {
        int updated = jdbc.update(
                "UPDATE transactions SET status_override = ?, applied = ? "
                        + "WHERE event_id = ? AND status_override IS NULL",
                status, "Sent".equals(status) ? 1 : 0, eventId);
        if (updated != 1) {
            throw new IllegalStateException(
                    "No pending transaction to transition for event " + eventId
                            + " (matched " + updated + " rows)");
        }
    }

    /**
     * Load a transaction by its Diakrisis decision event id. Used by the confirm step-up to recover the
     * server-side amount/account/owner of the originally scored pending payment, so the client cannot
     * substitute a different amount or replay another customer's event id. Returns the most recent match
     * (newest first) when more than one row carries the same event id.
     */
    public Optional<Txn> findByEventId(String eventId) {
        try {
            Txn txn = jdbc.queryForObject(
                    "SELECT * FROM transactions WHERE event_id = ? ORDER BY created_epoch_ms DESC LIMIT 1",
                    MAPPER, eventId);
            return Optional.ofNullable(txn);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
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
