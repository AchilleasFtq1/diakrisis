package com.cy.demobank.repo;

import com.cy.demobank.domain.Deposit;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** JdbcTemplate access to the {@code deposits} table. */
@Repository
public class DepositRepository {

    private static final RowMapper<Deposit> ROW_MAPPER = (rs, rowNum) -> new Deposit(
            rs.getString("id"),
            rs.getString("account_id"),
            rs.getLong("principal_cents"),
            rs.getLong("maturity_epoch_ms"),
            rs.getLong("penalty_cents"),
            rs.getInt("broken") != 0);

    private final JdbcTemplate jdbc;

    public DepositRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Deposit> findByAccount(String accountId) {
        return jdbc.query("SELECT * FROM deposits WHERE account_id = ? ORDER BY id", ROW_MAPPER, accountId);
    }

    public Optional<Deposit> findById(String id) {
        try {
            Deposit deposit = jdbc.queryForObject("SELECT * FROM deposits WHERE id = ?", ROW_MAPPER, id);
            return Optional.ofNullable(deposit);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public void insert(Deposit deposit) {
        jdbc.update("""
                INSERT INTO deposits(id, account_id, principal_cents, maturity_epoch_ms, penalty_cents, broken)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                deposit.id(), deposit.accountId(), deposit.principalCents(),
                deposit.maturityEpochMs(), deposit.penaltyCents(), deposit.broken() ? 1 : 0);
    }

    /** Mark a deposit as broken. Returns true if a not-yet-broken deposit was updated. */
    public boolean markBroken(String id) {
        return jdbc.update("UPDATE deposits SET broken = 1 WHERE id = ? AND broken = 0", id) == 1;
    }
}
