package com.cy.diakritis.decision.repo;

import com.cy.diakritis.common.persistence.AccountPostureItem;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.util.Optional;

/**
 * Reads and commits the rolling 72h account posture (freed funds / raised limit / beneficiary-add
 * activity) that drives the liquidation kill-chain signals.
 *
 * <p>pk = {@code ACC#<acct>}, sk = {@code POSTURE}.
 */
@Repository
public class AccountPostureRepository {

    private static final String ACCOUNT_PK_PREFIX = "ACC#";
    private static final String POSTURE_SK = "POSTURE";

    private final DynamoDbTable<AccountPostureItem> accountPostureTable;

    public AccountPostureRepository(DynamoDbTable<AccountPostureItem> accountPostureTable) {
        this.accountPostureTable = accountPostureTable;
    }

    public Optional<AccountPostureItem> find(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(accountPostureTable.getItem(keyFor(accountId)));
    }

    public void save(AccountPostureItem item) {
        accountPostureTable.putItem(item);
    }

    public static Key keyFor(String accountId) {
        return Key.builder()
                .partitionValue(ACCOUNT_PK_PREFIX + accountId)
                .sortValue(POSTURE_SK)
                .build();
    }

    public static String partitionKeyFor(String accountId) {
        return ACCOUNT_PK_PREFIX + accountId;
    }

    public static String sortKey() {
        return POSTURE_SK;
    }
}
