package com.cy.diakritis.decision.repo;

import com.cy.diakritis.common.persistence.DecisionItem;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.util.Optional;

/**
 * Persists and replays decision records for idempotency.
 *
 * <p>pk = {@code EVENT#<eventId>}, sk = {@code DECISION}. {@link #putIfAbsent} performs a conditional
 * put guarded by {@code attribute_not_exists(pk)}: the first writer for an {@code eventId} wins and
 * commits posture; a concurrent or replayed request loses the condition and re-reads the stored
 * record so the response body is byte-identical and no mutation is double-applied.
 */
@Repository
public class DecisionRepository {

    private static final String EVENT_PK_PREFIX = "EVENT#";
    private static final String DECISION_SK = "DECISION";
    private static final String NOT_EXISTS_PK = "attribute_not_exists(pk)";

    private final DynamoDbTable<DecisionItem> decisionTable;

    public DecisionRepository(DynamoDbTable<DecisionItem> decisionTable) {
        this.decisionTable = decisionTable;
    }

    public Optional<DecisionItem> findByEventId(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return Optional.empty();
        }
        Key key = Key.builder()
                .partitionValue(EVENT_PK_PREFIX + eventId)
                .sortValue(DECISION_SK)
                .build();
        return Optional.ofNullable(decisionTable.getItem(key));
    }

    public static String partitionKeyFor(String eventId) {
        return EVENT_PK_PREFIX + eventId;
    }

    public static String sortKey() {
        return DECISION_SK;
    }

    /**
     * Attempt to write {@code item} only if no decision exists for its key.
     *
     * @return {@code true} if this caller won the race (the put succeeded), {@code false} if a record
     * already existed (the conditional check failed) and the caller must re-read.
     */
    public boolean putIfAbsent(DecisionItem item) {
        try {
            decisionTable.putItem(PutItemEnhancedRequest.builder(DecisionItem.class)
                    .item(item)
                    .conditionExpression(Expression.builder().expression(NOT_EXISTS_PK).build())
                    .build());
            return true;
        } catch (ConditionalCheckFailedException alreadyExists) {
            return false;
        }
    }

    /** Overwrite the lifecycle state (and hold expiry) of an existing decision record. */
    public void updateLifecycle(DecisionItem item) {
        decisionTable.updateItem(UpdateItemEnhancedRequest.builder(DecisionItem.class)
                .item(item)
                .ignoreNulls(true)
                .build());
    }
}
