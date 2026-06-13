package com.cy.diakritis.ops.service;

import com.cy.diakritis.common.dto.Decision;
import com.cy.diakritis.common.dto.LifecycleState;
import com.cy.diakritis.common.dto.Outcome;
import com.cy.diakritis.common.persistence.CaseItem;
import com.cy.diakritis.common.persistence.DecisionItem;
import com.cy.diakritis.common.persistence.OutcomeItem;
import com.cy.diakritis.ops.web.dto.ApprovalEntry;
import com.cy.diakritis.ops.web.dto.CountersView;
import com.cy.diakritis.ops.web.dto.FeedEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

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

    private static final Logger LOG = LoggerFactory.getLogger(OpsService.class);

    private static final int FEED_LIMIT = 100;

    private final DynamoDbTable<DecisionItem> decisionTable;
    private final DynamoDbTable<CaseItem> caseTable;
    private final DynamoDbTable<OutcomeItem> outcomeTable;
    private final JsonMapper jsonMapper;

    public OpsService(DynamoDbTable<DecisionItem> decisionTable, DynamoDbTable<CaseItem> caseTable,
                      DynamoDbTable<OutcomeItem> outcomeTable, JsonMapper jsonMapper) {
        this.decisionTable = decisionTable;
        this.caseTable = caseTable;
        this.outcomeTable = outcomeTable;
        this.jsonMapper = jsonMapper;
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

    /**
     * Aggregate counters for the ops console: the lifecycle breakdown, the headline operating metrics
     * (SCA-exemption rate, p50 latency) computed over the {@code Decisions} table, and the SDD §9.5
     * feedback-loop counters (confirmed saves, false positives, money saved) computed over the
     * {@code Outcomes} table.
     */
    public CountersView counters() {
        Map<String, Long> byState = new TreeMap<>();
        long total = 0;
        long scaExemptCount = 0;
        List<Long> latencies = new ArrayList<>();

        for (DecisionItem item : decisionTable.scan().items()) {
            total++;
            String state = item.getLifecycleState() == null ? "UNKNOWN" : item.getLifecycleState();
            byState.merge(state, 1L, Long::sum);

            Decision decision = parseDecision(item);
            if (decision == null || decision.engineVerdict() == null) {
                continue;
            }
            if (decision.engineVerdict().scaExempt()) {
                scaExemptCount++;
            }
            latencies.add(decision.latencyMs());
        }

        long confirmedSaves = 0;
        long falsePositives = 0;
        long moneySavedCents = 0;
        for (OutcomeItem outcome : outcomeTable.scan().items()) {
            if (Outcome.CONFIRMED_SAVE.name().equals(outcome.getOutcome())) {
                confirmedSaves++;
                moneySavedCents += outcome.getAmountCents();
            } else if (Outcome.FALSE_POSITIVE.name().equals(outcome.getOutcome())) {
                falsePositives++;
            }
        }

        double exemptionRate = total == 0 ? 0.0 : (double) scaExemptCount / total;
        long p50LatencyMs = p50(latencies);

        return new CountersView(total, byState, confirmedSaves, falsePositives,
                moneySavedCents, exemptionRate, p50LatencyMs);
    }

    /** Parse the stored verbatim {@code Decision} response; null (logged) if it cannot be read. */
    private Decision parseDecision(DecisionItem item) {
        if (item.getResponseJson() == null || item.getResponseJson().isBlank()) {
            return null;
        }
        try {
            return jsonMapper.readValue(item.getResponseJson(), Decision.class);
        } catch (JacksonException ex) {
            LOG.warn("Could not parse stored decision for event {}: {}",
                    item.getEventId(), ex.toString());
            return null;
        }
    }

    /** Median (p50) of the supplied latencies; 0 when empty. Lower-median on an even count. */
    private static long p50(List<Long> latencies) {
        if (latencies.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(latencies);
        sorted.sort(Comparator.naturalOrder());
        return sorted.get((sorted.size() - 1) / 2);
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
