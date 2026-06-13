package com.cy.diakritis.decision.repo;

import com.cy.diakritis.common.dto.Outcome;
import com.cy.diakritis.common.persistence.OutcomeItem;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.util.Optional;

/**
 * Persists and counts decision {@link OutcomeItem outcomes} — the SDD §9.5 training signal. One row
 * is written per event (pk = {@code OUTCOME#<eventId>}, sk = {@code OUTCOME}); the counters
 * projection tallies each {@link Outcome} class.
 *
 * <p>The demo's outcome volume is small, so the per-class tally is a bounded scan with a server-side
 * filter — consistent with the existing ops read-side projections (which also scan).
 */
@Repository
public class OutcomeRepository {

    private static final String OUTCOME_PK_PREFIX = "OUTCOME#";
    private static final String OUTCOME_SK = "OUTCOME";

    private final DynamoDbTable<OutcomeItem> outcomeTable;

    public OutcomeRepository(DynamoDbTable<OutcomeItem> outcomeTable) {
        this.outcomeTable = outcomeTable;
    }

    public Optional<OutcomeItem> find(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return Optional.empty();
        }
        Key key = Key.builder()
                .partitionValue(OUTCOME_PK_PREFIX + eventId)
                .sortValue(OUTCOME_SK)
                .build();
        return Optional.ofNullable(outcomeTable.getItem(key));
    }

    public void save(OutcomeItem item) {
        outcomeTable.putItem(item);
    }

    /** Count the rows whose recorded outcome equals {@code outcome}. */
    public long count(Outcome outcome) {
        String label = outcome.name();
        long count = 0L;
        for (OutcomeItem item : outcomeTable.scan().items()) {
            if (label.equals(item.getOutcome())) {
                count++;
            }
        }
        return count;
    }

    public static String partitionKeyFor(String eventId) {
        return OUTCOME_PK_PREFIX + eventId;
    }

    public static String sortKey() {
        return OUTCOME_SK;
    }
}
