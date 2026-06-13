package com.cy.diakritis.decision.repo;

import com.cy.diakritis.common.persistence.CounterpartyReputationItem;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.util.Optional;

/**
 * Reads and commits cross-account counterparty reputation (flag history / worst outcome), updated by
 * a winning decision when an action is flagged.
 *
 * <p>pk = {@code CP#<cpKey>}, sk = {@code REP}.
 */
@Repository
public class CounterpartyReputationRepository {

    private static final String CP_PK_PREFIX = "CP#";
    private static final String REP_SK = "REP";

    private final DynamoDbTable<CounterpartyReputationItem> counterpartyReputationTable;

    public CounterpartyReputationRepository(DynamoDbTable<CounterpartyReputationItem> counterpartyReputationTable) {
        this.counterpartyReputationTable = counterpartyReputationTable;
    }

    public Optional<CounterpartyReputationItem> find(String cpKey) {
        if (cpKey == null || cpKey.isBlank()) {
            return Optional.empty();
        }
        Key key = Key.builder()
                .partitionValue(CP_PK_PREFIX + cpKey)
                .sortValue(REP_SK)
                .build();
        return Optional.ofNullable(counterpartyReputationTable.getItem(key));
    }

    public void save(CounterpartyReputationItem item) {
        counterpartyReputationTable.putItem(item);
    }

    public static String partitionKeyFor(String cpKey) {
        return CP_PK_PREFIX + cpKey;
    }

    public static String sortKey() {
        return REP_SK;
    }
}
