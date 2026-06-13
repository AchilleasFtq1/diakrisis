package com.cy.demobank.config;

import com.cy.demobank.domain.Account;
import com.cy.demobank.domain.Deposit;
import com.cy.demobank.domain.Payee;
import com.cy.demobank.repo.AccountRepository;
import com.cy.demobank.repo.DepositRepository;
import com.cy.demobank.repo.PayeeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

/**
 * Runs {@code schema.sql} (drop + recreate) and seeds the disclosed demo accounts on every boot so
 * demos are deterministic. The seed mirrors the Diakrisis ETL demo fixtures so the verdicts line up:
 *
 * <ul>
 *   <li><b>acc-A</b> (customer-A): retail, €4500.00, two ESTABLISHED payees ({@code CD|46939146},
 *       {@code KL|64831554}) — the Berka-derived counterparties that score ALLOW for a normal transfer.</li>
 *   <li><b>acc-B</b> (customer-B): €4980.00 plus the constructed term deposit {@code dep-001}
 *       (€5000.00 principal, €125 penalty) — the kill-chain fixture: break it, then drain to a new
 *       payee and the decision-service returns HOLD (liquidation_kill_chain).</li>
 *   <li><b>acc-C</b> (customer-C): retail, €9000.00.</li>
 * </ul>
 */
@Component
public class SqliteSchemaInitializer implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteSchemaInitializer.class);

    /** Diakrisis demo balances (cents) — mirror the ETL DemoSeed so demo-bank facts match the engine. */
    private static final long ACC_A_BALANCE_CENTS = 450_000L;   // €4500.00
    private static final long ACC_B_BALANCE_CENTS = 498_000L;   // €4980.00
    private static final long ACC_C_BALANCE_CENTS = 900_000L;   // €9000.00

    /** The kill-chain term deposit on acc-B (constructed Diakrisis fixture dep-001). */
    private static final String KILL_CHAIN_DEPOSIT_ID = "dep-001";
    private static final long DEPOSIT_PRINCIPAL_CENTS = 500_000L; // €5000.00
    private static final long DEPOSIT_PENALTY_CENTS = 12_500L;    // €125.00

    /** acc-A established counterparties (Berka aggregates the engine treats as known history). */
    private static final String CP_A1 = "CD|46939146";
    private static final String CP_A2 = "KL|64831554";

    private final DataSource dataSource;
    private final AccountRepository accountRepository;
    private final PayeeRepository payeeRepository;
    private final DepositRepository depositRepository;

    public SqliteSchemaInitializer(DataSource dataSource,
                                   AccountRepository accountRepository,
                                   PayeeRepository payeeRepository,
                                   DepositRepository depositRepository) {
        this.dataSource = dataSource;
        this.accountRepository = accountRepository;
        this.payeeRepository = payeeRepository;
        this.depositRepository = depositRepository;
    }

    @Override
    public void run(ApplicationArguments args) throws SQLException {
        applySchema();
        seed();
        LOG.info("demo-bank SQLite schema applied and demo accounts seeded");
    }

    private void applySchema() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
    }

    private void seed() {
        Instant now = Instant.now();
        // Established payees were first seen well in the past so they read as long-standing history.
        long establishedEpochMs = now.minus(Duration.ofDays(120)).toEpochMilli();

        // acc-A: retail with two established payees → a normal transfer to either is ALLOW.
        accountRepository.insert(new Account(
                "acc-A", "Demo Retail Account A", ACC_A_BALANCE_CENTS, "customer-A", false, false));
        payeeRepository.insert(new Payee(
                "acc-A", CP_A1, CP_A1, "CD Supplier", "CD Supplier", establishedEpochMs));
        payeeRepository.insert(new Payee(
                "acc-A", CP_A2, CP_A2, "KL Supplier", "KL Supplier", establishedEpochMs));

        // acc-B: holds the kill-chain term deposit dep-001.
        accountRepository.insert(new Account(
                "acc-B", "Demo Account B", ACC_B_BALANCE_CENTS, "customer-B", false, false));
        depositRepository.insert(new Deposit(
                KILL_CHAIN_DEPOSIT_ID, "acc-B", DEPOSIT_PRINCIPAL_CENTS,
                now.plus(Duration.ofDays(180)).toEpochMilli(), DEPOSIT_PENALTY_CENTS, false));

        // acc-C: retail.
        accountRepository.insert(new Account(
                "acc-C", "Demo Account C", ACC_C_BALANCE_CENTS, "customer-C", false, false));
    }
}
