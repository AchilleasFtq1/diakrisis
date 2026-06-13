package com.cy.diakritis.decision.repo;

import com.cy.diakritis.common.persistence.AccountStatsItem;
import com.cy.diakritis.common.persistence.CounterpartyBaselineItem;
import com.cy.diakritis.common.persistence.CounterpartyByNameItem;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.util.Optional;

/**
 * Read access to the offline-computed feature baselines: per-counterparty payment history
 * ({@code CounterpartyBaseline}), per-account outgoing statistics ({@code AccountStats}) and the
 * name-indexed Confirmation-of-Payee record ({@code CounterpartyByName}). These tables are written
 * by the ETL and read-only on the decision hot path.
 */
@Repository
public class FeatureRepository {

    private static final String ACCOUNT_PK_PREFIX = "ACC#";
    private static final String CP_SK_PREFIX = "CP#";
    private static final String META_SK = "META";
    private static final String NAME_SK_PREFIX = "NAME#";

    private final DynamoDbTable<CounterpartyBaselineItem> baselineTable;
    private final DynamoDbTable<AccountStatsItem> accountStatsTable;
    private final DynamoDbTable<CounterpartyByNameItem> byNameTable;

    public FeatureRepository(DynamoDbTable<CounterpartyBaselineItem> counterpartyBaselineTable,
                             DynamoDbTable<AccountStatsItem> accountStatsTable,
                             DynamoDbTable<CounterpartyByNameItem> counterpartyByNameTable) {
        this.baselineTable = counterpartyBaselineTable;
        this.accountStatsTable = accountStatsTable;
        this.byNameTable = counterpartyByNameTable;
    }

    public Optional<CounterpartyBaselineItem> baseline(String accountId, String cpKey) {
        if (isBlank(accountId) || isBlank(cpKey)) {
            return Optional.empty();
        }
        Key key = Key.builder()
                .partitionValue(ACCOUNT_PK_PREFIX + accountId)
                .sortValue(CP_SK_PREFIX + cpKey)
                .build();
        return Optional.ofNullable(baselineTable.getItem(key));
    }

    public Optional<AccountStatsItem> accountStats(String accountId) {
        if (isBlank(accountId)) {
            return Optional.empty();
        }
        Key key = Key.builder()
                .partitionValue(ACCOUNT_PK_PREFIX + accountId)
                .sortValue(META_SK)
                .build();
        return Optional.ofNullable(accountStatsTable.getItem(key));
    }

    public Optional<CounterpartyByNameItem> byName(String accountId, String normalizedName) {
        if (isBlank(accountId) || isBlank(normalizedName)) {
            return Optional.empty();
        }
        Key key = Key.builder()
                .partitionValue(ACCOUNT_PK_PREFIX + accountId)
                .sortValue(NAME_SK_PREFIX + normalizedName.toUpperCase())
                .build();
        return Optional.ofNullable(byNameTable.getItem(key));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
