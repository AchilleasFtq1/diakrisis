package com.cy.diakritis.decision.repo;

import com.cy.diakritis.common.persistence.ObservationItem;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes behavioural observations (last-seen of device / IP / session / counterparty
 * values) used by the M1 recency features and committed by the winning decision.
 *
 * <p>pk = {@code OBS#<acct>}, sk = {@code KIND#<value>}.
 */
@Repository
public class ObservationRepository {

    private static final String OBS_PK_PREFIX = "OBS#";
    private static final String SK_SEPARATOR = "#";

    private final DynamoDbTable<ObservationItem> observationTable;

    public ObservationRepository(DynamoDbTable<ObservationItem> observationTable) {
        this.observationTable = observationTable;
    }

    public Optional<ObservationItem> find(String accountId, String kind, String value) {
        if (isBlank(accountId) || isBlank(kind) || value == null) {
            return Optional.empty();
        }
        Key key = Key.builder()
                .partitionValue(OBS_PK_PREFIX + accountId)
                .sortValue(kind + SK_SEPARATOR + value)
                .build();
        return Optional.ofNullable(observationTable.getItem(key));
    }

    /**
     * Every observation of the given {@code kind} for an account, found by querying the account's
     * {@code OBS#<acct>} partition with a {@code begins_with(sk, "<kind>#")} condition. The G1/G2/D2
     * signals build their familiar-value baseline from this; D1/P1 read individual rows via
     * {@link #find}. The list is empty (not null) when the account has no observation of the kind.
     */
    public List<ObservationItem> queryByKind(String accountId, String kind) {
        if (isBlank(accountId) || isBlank(kind)) {
            return List.of();
        }
        QueryConditional condition = QueryConditional.sortBeginsWith(Key.builder()
                .partitionValue(OBS_PK_PREFIX + accountId)
                .sortValue(kind + SK_SEPARATOR)
                .build());
        List<ObservationItem> results = new ArrayList<>();
        observationTable.query(QueryEnhancedRequest.builder().queryConditional(condition).build())
                .items()
                .forEach(results::add);
        return results;
    }

    public void save(ObservationItem item) {
        observationTable.putItem(item);
    }

    public static String partitionKeyFor(String accountId) {
        return OBS_PK_PREFIX + accountId;
    }

    public static String sortKeyFor(String kind, String value) {
        return kind + SK_SEPARATOR + value;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
