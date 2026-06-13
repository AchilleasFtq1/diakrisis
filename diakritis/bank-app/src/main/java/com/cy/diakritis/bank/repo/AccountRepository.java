package com.cy.diakritis.bank.repo;

import com.cy.diakritis.common.persistence.AccountItem;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.util.Optional;

/**
 * Reads bank-owned account facts ({@code ACC#<acct>} / {@code META}) used to build ActionEvents.
 */
@Repository
public class AccountRepository {

    private static final String ACCOUNT_PK_PREFIX = "ACC#";
    private static final String META_SK = "META";

    private final DynamoDbTable<AccountItem> accountTable;

    public AccountRepository(DynamoDbTable<AccountItem> accountTable) {
        this.accountTable = accountTable;
    }

    public Optional<AccountItem> findById(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return Optional.empty();
        }
        Key key = Key.builder()
                .partitionValue(ACCOUNT_PK_PREFIX + accountId)
                .sortValue(META_SK)
                .build();
        return Optional.ofNullable(accountTable.getItem(key));
    }

    public void save(AccountItem item) {
        accountTable.putItem(item);
    }
}
