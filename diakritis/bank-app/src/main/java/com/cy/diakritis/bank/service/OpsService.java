package com.cy.diakritis.bank.service;

import com.cy.diakritis.bank.web.dto.ApprovalEntry;
import com.cy.diakritis.bank.web.dto.CountersView;
import com.cy.diakritis.bank.web.dto.FeedEntry;
import com.cy.diakritis.common.dto.LifecycleState;
import com.cy.diakritis.common.persistence.CaseItem;
import com.cy.diakritis.common.persistence.DecisionItem;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Read-side projections for the ops console. The feed and counters are computed over the
 * {@code Decisions} table; the approvals queue is computed over the {@code Cases} table filtered to
 * approval-pending states. Reads are bounded scans appropriate for the demo's data volumes.
 */
@Service
public class OpsService {

    private static final int FEED_LIMIT = 100;

    private final DynamoDbTable<DecisionItem> decisionTable;
    private final DynamoDbTable<CaseItem> caseTable;

    public OpsService(DynamoDbTable<DecisionItem> decisionTable, DynamoDbTable<CaseItem> caseTable) {
        this.decisionTable = decisionTable;
        this.caseTable = caseTable;
    }

    public List<FeedEntry> feed() {
        List<FeedEntry> entries = new ArrayList<>();
        decisionTable.scan().items().forEach(item -> entries.add(toFeedEntry(item)));
        entries.sort(Comparator.comparing(
                (FeedEntry entry) -> entry.createdAt() == null ? Instant.EPOCH : entry.createdAt())
                .reversed());
        if (entries.size() > FEED_LIMIT) {
            return new ArrayList<>(entries.subList(0, FEED_LIMIT));
        }
        return entries;
    }

    public CountersView counters() {
        Map<String, Long> byState = new TreeMap<>();
        long total = 0;
        for (DecisionItem item : decisionTable.scan().items().stream().toList()) {
            total++;
            String state = item.getLifecycleState() == null ? "UNKNOWN" : item.getLifecycleState();
            byState.merge(state, 1L, Long::sum);
        }
        return new CountersView(total, byState);
    }

    public List<ApprovalEntry> approvals() {
        List<ApprovalEntry> entries = new ArrayList<>();
        for (CaseItem item : caseTable.scan().items().stream().toList()) {
            if (isApprovalPending(item.getState())) {
                entries.add(toApprovalEntry(item));
            }
        }
        entries.sort(Comparator.comparingLong(
                (ApprovalEntry entry) -> entry.createdAt() == null ? 0L : entry.createdAt().toEpochMilli()));
        return entries;
    }

    private boolean isApprovalPending(String state) {
        if (state == null) {
            return false;
        }
        return LifecycleState.PENDING_APPROVAL.name().equals(state)
                || LifecycleState.REVIEW.name().equals(state)
                || LifecycleState.HELD.name().equals(state);
    }

    private FeedEntry toFeedEntry(DecisionItem item) {
        return new FeedEntry(
                item.getEventId(),
                item.getAccountId(),
                item.getInitiatorSub(),
                item.getLifecycleState(),
                item.getCreatedEpochMs() > 0 ? Instant.ofEpochMilli(item.getCreatedEpochMs()) : null,
                item.getHoldExpiresEpochMs() > 0 ? Instant.ofEpochMilli(item.getHoldExpiresEpochMs()) : null);
    }

    private ApprovalEntry toApprovalEntry(CaseItem item) {
        return new ApprovalEntry(
                item.getEventId(),
                item.getState(),
                item.getInitiatorUserId(),
                item.getHoldExpiryEpochMs() > 0 ? Instant.ofEpochMilli(item.getHoldExpiryEpochMs()) : null,
                item.getBatchHeldItemIds(),
                item.getCreatedEpochMs() > 0 ? Instant.ofEpochMilli(item.getCreatedEpochMs()) : null);
    }
}
