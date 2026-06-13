package com.cy.demobank.repo;

import com.cy.demobank.domain.Payee;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** JdbcTemplate access to the {@code payees} table. */
@Repository
public class PayeeRepository {

    private static final RowMapper<Payee> ROW_MAPPER = (rs, rowNum) -> new Payee(
            rs.getString("account_id"),
            rs.getString("cp_key"),
            rs.getString("iban"),
            rs.getString("bic"),
            rs.getString("display_name"),
            rs.getString("resolved_name"),
            rs.getLong("created_epoch_ms"));

    private final JdbcTemplate jdbc;

    public PayeeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Payee> findByAccount(String accountId) {
        return jdbc.query("SELECT * FROM payees WHERE account_id = ? ORDER BY created_epoch_ms",
                ROW_MAPPER, accountId);
    }

    public Optional<Payee> find(String accountId, String cpKey) {
        try {
            Payee payee = jdbc.queryForObject(
                    "SELECT * FROM payees WHERE account_id = ? AND cp_key = ?", ROW_MAPPER, accountId, cpKey);
            return Optional.ofNullable(payee);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public void insert(Payee payee) {
        jdbc.update("""
                INSERT INTO payees(account_id, cp_key, iban, bic, display_name, resolved_name, created_epoch_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                payee.accountId(), payee.cpKey(), payee.iban(), payee.bic(), payee.displayName(),
                payee.resolvedName(), payee.createdEpochMs());
    }

    public boolean exists(String accountId, String cpKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payees WHERE account_id = ? AND cp_key = ?", Integer.class, accountId, cpKey);
        return count != null && count > 0;
    }
}
