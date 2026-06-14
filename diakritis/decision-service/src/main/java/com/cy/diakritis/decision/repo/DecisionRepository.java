package com.cy.diakritis.decision.repo;

import com.cy.diakritis.common.persistence.DecisionItem;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.util.Map;
import java.util.Optional;

/**
 * Persists and replays decision records for idempotency.
 *
 * <p>pk = {@code EVENT#<eventId>}, sk = {@code DECISION}. {@link #putIfAbsent} performs a conditional
 * put guarded by {@code attribute_not_exists(pk)}: the first writer for an {@code eventId} wins and the
 * row becomes the commit-completion marker (the idempotent side-effects are committed before the put);
 * a concurrent or replayed request loses the condition and re-reads the stored record so the response
 * body is byte-identical and no mutation is double-applied.
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

    /**
     * Atomically transition the lifecycle state of an existing decision record, but ONLY if the stored
     * state still equals {@code expectedSourceState}. This is an optimistic compare-and-set: of two
     * concurrent transitions reading the same source state, exactly one wins the conditional update and
     * the other fails the condition — so a four-eyes action can never be EXECUTED twice, and an approve
     * racing a reject cannot both apply. The losing caller gets a {@code false} return and must abort
     * its side effects.
     *
     * <p>The DynamoDB attribute backing {@code DecisionItem.lifecycleState} is named
     * {@value #LIFECYCLE_STATE_ATTR} (the enhanced-client default for the {@code lifecycleState}
     * property); the condition asserts {@code lifecycleState = :expected}.
     *
     * @return {@code true} if this caller won the transition (the conditional update committed),
     * {@code false} if the stored state no longer matched {@code expectedSourceState} (a concurrent
     * transition already moved it).
     */
    public boolean updateLifecycle(DecisionItem item, String expectedSourceState) {
        try {
            decisionTable.updateItem(UpdateItemEnhancedRequest.builder(DecisionItem.class)
                    .item(item)
                    .ignoreNulls(true)
                    .conditionExpression(Expression.builder()
                            .expression("#state = :expected")
                            .putExpressionName("#state", LIFECYCLE_STATE_ATTR)
                            .expressionValues(Map.of(":expected",
                                    AttributeValue.fromS(expectedSourceState)))
                            .build())
                    .build());
            return true;
        } catch (ConditionalCheckFailedException raced) {
            return false;
        }
    }

    private static final String LIFECYCLE_STATE_ATTR = "lifecycleState";
}
