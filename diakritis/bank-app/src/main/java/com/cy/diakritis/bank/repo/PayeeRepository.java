package com.cy.diakritis.bank.repo;

import com.cy.diakritis.common.persistence.PayeeItem;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads saved payees for an account ({@code ACC#<acct>} / {@code PAYEE#<cpKey>}).
 */
@Repository
public class PayeeRepository {

    private static final String ACCOUNT_PK_PREFIX = "ACC#";
    private static final String PAYEE_SK_PREFIX = "PAYEE#";

    private final DynamoDbTable<PayeeItem> payeeTable;

    public PayeeRepository(DynamoDbTable<PayeeItem> payeeTable) {
        this.payeeTable = payeeTable;
    }

    public List<PayeeItem> findByAccount(String accountId) {
        List<PayeeItem> payees = new ArrayList<>();
        if (accountId == null || accountId.isBlank()) {
            return payees;
        }
        QueryConditional condition = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(ACCOUNT_PK_PREFIX + accountId).build());
        payeeTable.query(condition).items().forEach(payees::add);
        return payees;
    }

    public Optional<PayeeItem> findByAccountAndKey(String accountId, String counterpartyKey) {
        if (accountId == null || accountId.isBlank() || counterpartyKey == null || counterpartyKey.isBlank()) {
            return Optional.empty();
        }
        Key key = Key.builder()
                .partitionValue(ACCOUNT_PK_PREFIX + accountId)
                .sortValue(PAYEE_SK_PREFIX + counterpartyKey)
                .build();
        return Optional.ofNullable(payeeTable.getItem(key));
    }

    public void save(PayeeItem item) {
        payeeTable.putItem(item);
    }
}
