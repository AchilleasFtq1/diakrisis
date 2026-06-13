package com.cy.diakritis.decision.repo;

import com.cy.diakritis.common.persistence.ObservationItem;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

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
