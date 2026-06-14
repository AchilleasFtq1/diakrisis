package com.cy.diakritis.etl;

import com.cy.diakritis.common.persistence.TableSchema;
import com.cy.diakritis.common.persistence.Tables;

import java.util.List;

/**
 * Key schemas for the tables the ETL writes. All Diakrisis tables use a string {@code pk}/{@code sk}
 * pair; these definitions drive create-if-missing bootstrapping before any put.
 */
final class EtlSchemas {

    private EtlSchemas() {
    }

    /** Feature tables populated from real Berka aggregates on every run. */
    static List<TableSchema> featureTables() {
        return List.of(
                TableSchema.of(Tables.COUNTERPARTY_BASELINE, "pk", "sk"),
                TableSchema.of(Tables.ACCOUNT_STATS, "pk", "sk"),
                TableSchema.of(Tables.COUNTERPARTY_BY_NAME, "pk", "sk"));
    }

    /** Additional tables the {@code --demo} seed writes into. */
    static List<TableSchema> demoTables() {
        return List.of(
                TableSchema.of(Tables.ACCOUNTS, "pk", "sk"),
                TableSchema.of(Tables.PAYEES, "pk", "sk"),
                // The T7-T15 seed establishes behavioural baselines (device / country / alias
                // resolution), a freed-funds posture, and a cross-account reputation flag so
                // G1/D1/P1/K1/X1 fire on the demo's next action exactly as in the golden-path suite.
                TableSchema.of(Tables.OBSERVATIONS, "pk", "sk", "ttlEpochSec"),
                TableSchema.of(Tables.ACCOUNT_POSTURE, "pk", "sk", "ttlEpochSec"),
                TableSchema.of(Tables.COUNTERPARTY_REPUTATION, "pk", "sk", "ttlEpochSec"));
    }
}
