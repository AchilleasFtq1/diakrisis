package com.cy.diakritis.decision.repo;

import com.cy.diakritis.common.persistence.CaseItem;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.util.Optional;

/**
 * Reads and writes lifecycle cases for held / approval-pending decisions.
 *
 * <p>pk = {@code CASE#<eventId>}, sk = {@code CASE}.
 */
@Repository
public class CaseRepository {

    private static final String CASE_PK_PREFIX = "CASE#";
    private static final String CASE_SK = "CASE";

    private final DynamoDbTable<CaseItem> caseTable;

    public CaseRepository(DynamoDbTable<CaseItem> caseTable) {
        this.caseTable = caseTable;
    }

    public Optional<CaseItem> findByEventId(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return Optional.empty();
        }
        Key key = Key.builder()
                .partitionValue(CASE_PK_PREFIX + eventId)
                .sortValue(CASE_SK)
                .build();
        return Optional.ofNullable(caseTable.getItem(key));
    }

    public void save(CaseItem item) {
        caseTable.putItem(item);
    }

    public static String partitionKeyFor(String eventId) {
        return CASE_PK_PREFIX + eventId;
    }

    public static String sortKey() {
        return CASE_SK;
    }
}
