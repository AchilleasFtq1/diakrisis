package com.cy.demobank.repo;

import com.cy.demobank.domain.Account;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** JdbcTemplate access to the {@code accounts} table. */
@Repository
public class AccountRepository {

    private static final RowMapper<Account> ROW_MAPPER = (rs, rowNum) -> new Account(
            rs.getString("id"),
            rs.getString("display_name"),
            rs.getLong("available_balance_cents"),
            rs.getString("owner_user"),
            rs.getInt("is_business") != 0,
            rs.getInt("vulnerable") != 0);

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Account> findAll() {
        return jdbc.query("SELECT * FROM accounts ORDER BY id", ROW_MAPPER);
    }

    public Optional<Account> findById(String id) {
        try {
            Account account = jdbc.queryForObject("SELECT * FROM accounts WHERE id = ?", ROW_MAPPER, id);
            return Optional.ofNullable(account);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public void insert(Account account) {
        jdbc.update("""
                INSERT INTO accounts(id, display_name, available_balance_cents, owner_user, is_business, vulnerable)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                account.id(), account.displayName(), account.availableBalanceCents(),
                account.ownerUser(), account.business() ? 1 : 0, account.vulnerable() ? 1 : 0);
    }

    /**
     * Atomically debit an account by {@code amountCents}, guarding against overdraft. Returns true
     * if the row was updated (sufficient balance), false otherwise.
     */
    public boolean debitIfSufficient(String accountId, long amountCents) {
        int updated = jdbc.update("""
                UPDATE accounts SET available_balance_cents = available_balance_cents - ?
                WHERE id = ? AND available_balance_cents >= ?
                """, amountCents, accountId, amountCents);
        return updated == 1;
    }

    /** Credit an account by {@code amountCents} (e.g. crediting freed deposit principal). */
    public void credit(String accountId, long amountCents) {
        jdbc.update("UPDATE accounts SET available_balance_cents = available_balance_cents + ? WHERE id = ?",
                amountCents, accountId);
    }
}
