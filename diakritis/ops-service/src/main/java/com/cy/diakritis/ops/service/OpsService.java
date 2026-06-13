package com.cy.diakritis.ops.service;

import com.cy.diakritis.common.dto.Decision;
import com.cy.diakritis.common.dto.LifecycleState;
import com.cy.diakritis.common.dto.Outcome;
import com.cy.diakritis.common.persistence.AccountPostureItem;
import com.cy.diakritis.common.persistence.CaseItem;
import com.cy.diakritis.common.persistence.DecisionItem;
import com.cy.diakritis.common.persistence.ObservationItem;
import com.cy.diakritis.common.persistence.OutcomeItem;
import com.cy.diakritis.ops.web.dto.AccountView;
import com.cy.diakritis.ops.web.dto.ApprovalEntry;
import com.cy.diakritis.ops.web.dto.CountersView;
import com.cy.diakritis.ops.web.dto.FeedEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-side projections for the ops console. The feed/counters are computed over the {@code Decisions}
 * table (the engine detail is parsed from the stored verbatim decision response), the approvals queue
 * over the {@code Cases} table, and the account view over {@code AccountPosture} + {@code Observations}.
 * Reads are bounded scans appropriate for the demo's data volumes.
 */
@Service
public class OpsService {

    private static final Logger LOG = LoggerFactory.getLogger(OpsService.class);

    private static final int FEED_LIMIT = 100;
    /** Pulls the event type out of the audit explanation ("decision=… type=TRANSFER typologies=…"). */
    private static final Pattern EVENT_TYPE = Pattern.compile("type=([A-Z_]+)");

    private final DynamoDbTable<DecisionItem> decisionTable;
    private final DynamoDbTable<CaseItem> caseTable;
    private final DynamoDbTable<OutcomeItem> outcomeTable;
    private final DynamoDbTable<AccountPostureItem> postureTable;
    private final DynamoDbTable<ObservationItem> observationTable;
    private final JsonMapper jsonMapper;

    public OpsService(DynamoDbTable<DecisionItem> decisionTable, DynamoDbTable<CaseItem> caseTable,
                      DynamoDbTable<OutcomeItem> outcomeTable, DynamoDbTable<AccountPostureItem> postureTable,
                      DynamoDbTable<ObservationItem> observationTable, JsonMapper jsonMapper) {
        this.decisionTable = decisionTable;
        this.caseTable = caseTable;
        this.outcomeTable = outcomeTable;
        this.postureTable = postureTable;
        this.observationTable = observationTable;
        this.jsonMapper = jsonMapper;
    }

    // ------------------------------------------------------------------------------------------------
    // Feed.
    // ------------------------------------------------------------------------------------------------

    public List<FeedEntry> feed() {
        List<FeedEntry> entries = new ArrayList<>();
        decisionTable.scan().items().forEach(item -> entries.add(toFeedEntry(item)));
        entries.sort(Comparator.comparing(
                (FeedEntry entry) -> entry.createdAt() == null ? Instant.EPOCH : entry.createdAt())
                .reversed());
        return entries.size() > FEED_LIMIT ? new ArrayList<>(entries.subList(0, FEED_LIMIT)) : entries;
    }

    private FeedEntry toFeedEntry(DecisionItem item) {
        Decision decision = parseDecision(item);
        String verdict = null;
        Integer score = null;
        List<String> typologies = null;
        String reasonCode = null;
        String friction = null;
        String eventType = null;
        if (decision != null) {
            if (decision.combined() != null && decision.combined().decision() != null) {
                verdict = decision.combined().decision().name();
            } else if (decision.engineVerdict() != null && decision.engineVerdict().decision() != null) {
                verdict = decision.engineVerdict().decision().name();
            }
            if (decision.engineVerdict() != null) {
                score = decision.engineVerdict().score();
                typologies = decision.engineVerdict().typologies();
                friction = decision.engineVerdict().friction() == null
                        ? null : decision.engineVerdict().friction().name();
            }
            reasonCode = decision.reasonCode();
            eventType = eventTypeFrom(decision);
        }
        return new FeedEntry(
                item.getEventId(),
                item.getAccountId(),
                item.getInitiatorSub(),
                item.getLifecycleState(),
                item.getCreatedEpochMs() > 0 ? Instant.ofEpochMilli(item.getCreatedEpochMs()) : null,
                item.getHoldExpiresEpochMs() > 0 ? Instant.ofEpochMilli(item.getHoldExpiresEpochMs()) : null,
                verdict, score, typologies, reasonCode, friction, eurOf(item.getAmountCents()), eventType);
    }

    /** The event type isn't a stored column; it is recovered from the audit explanation, else null. */
    private static String eventTypeFrom(Decision decision) {
        if (decision.explanation() == null || decision.explanation().audit() == null) {
            return null;
        }
        Matcher m = EVENT_TYPE.matcher(decision.explanation().audit());
        return m.find() ? m.group(1) : null;
    }

    // ------------------------------------------------------------------------------------------------
    // Counters.
    // ------------------------------------------------------------------------------------------------

    public CountersView counters() {
        Map<String, Long> byState = new TreeMap<>();
        Map<String, Long> byOutcome = new TreeMap<>();
        long total = 0;
        long scaExemptCount = 0;
        List<Long> latencies = new ArrayList<>();

        for (DecisionItem item : decisionTable.scan().items()) {
            total++;
            byState.merge(item.getLifecycleState() == null ? "UNKNOWN" : item.getLifecycleState(), 1L, Long::sum);

            Decision decision = parseDecision(item);
            if (decision == null || decision.engineVerdict() == null) {
                continue;
            }
            String outcome = decision.combined() != null && decision.combined().decision() != null
                    ? decision.combined().decision().name()
                    : (decision.engineVerdict().decision() == null
                        ? "UNKNOWN" : decision.engineVerdict().decision().name());
            byOutcome.merge(outcome, 1L, Long::sum);
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
        return new CountersView(total, byState, byOutcome, confirmedSaves, falsePositives,
                moneySavedCents, exemptionRate, p50(latencies));
    }

    // ------------------------------------------------------------------------------------------------
    // Approvals.
    // ------------------------------------------------------------------------------------------------

    public List<ApprovalEntry> approvals() {
        Map<String, Long> amountByEvent = new HashMap<>();
        decisionTable.scan().items().forEach(d -> amountByEvent.put(d.getEventId(), d.getAmountCents()));

        List<ApprovalEntry> entries = new ArrayList<>();
        for (CaseItem item : caseTable.scan().items()) {
            if (isApprovalPending(item.getState())) {
                entries.add(toApprovalEntry(item, amountByEvent.get(item.getEventId())));
            }
        }
        entries.sort(Comparator.comparingLong(
                (ApprovalEntry entry) -> entry.createdAt() == null ? 0L : entry.createdAt().toEpochMilli()));
        return entries;
    }

    private boolean isApprovalPending(String state) {
        return state != null && (LifecycleState.PENDING_APPROVAL.name().equals(state)
                || LifecycleState.REVIEW.name().equals(state)
                || LifecycleState.HELD.name().equals(state));
    }

    private ApprovalEntry toApprovalEntry(CaseItem item, Long amountCents) {
        return new ApprovalEntry(
                item.getEventId(),
                item.getState(),
                item.getInitiatorUserId(),
                item.getHoldExpiryEpochMs() > 0 ? Instant.ofEpochMilli(item.getHoldExpiryEpochMs()) : null,
                item.getBatchHeldItemIds(),
                item.getCreatedEpochMs() > 0 ? Instant.ofEpochMilli(item.getCreatedEpochMs()) : null,
                amountCents == null ? null : eurOf(amountCents));
    }

    // ------------------------------------------------------------------------------------------------
    // Decision detail + account view.
    // ------------------------------------------------------------------------------------------------

    /** The full stored decision for one event (the verbatim response, incl. the signal breakdown). */
    public JsonNode decision(String eventId) {
        for (DecisionItem item : decisionTable.scan().items()) {
            if (eventId.equals(item.getEventId()) && item.getResponseJson() != null) {
                try {
                    return jsonMapper.readTree(item.getResponseJson());
                } catch (JacksonException ex) {
                    LOG.warn("Could not parse stored decision for event {}: {}", eventId, ex.toString());
                    return null;
                }
            }
        }
        return null;
    }

    /** Posture + observations + decision history for one account (the kill-chain memory). */
    public AccountView accountView(String accountId) {
        String pk = "ACC#" + accountId;

        AccountView.Posture posture = null;
        for (AccountPostureItem p : postureTable.scan().items()) {
            if (pk.equals(p.getPk())) {
                posture = new AccountView.Posture(
                        eurOf(p.getFundsFreedEur72hCents()),
                        eurOf(p.getLimitRaised72hCents()),
                        p.getBeneficiaryAddCount72h());
                break;
            }
        }

        List<AccountView.Observation> observations = new ArrayList<>();
        for (ObservationItem o : observationTable.scan().items()) {
            if (accountId.equals(o.getAccountId())) {
                observations.add(new AccountView.Observation(o.getKind(), o.getValue(),
                        o.getFirstSeenEpochMs() > 0 ? Instant.ofEpochMilli(o.getFirstSeenEpochMs()) : null));
            }
        }
        observations.sort(Comparator.comparing(
                (AccountView.Observation o) -> o.firstSeenAt() == null ? Instant.EPOCH : o.firstSeenAt()));

        List<FeedEntry> history = new ArrayList<>();
        decisionTable.scan().items().forEach(item -> {
            if (accountId.equals(item.getAccountId())) {
                history.add(toFeedEntry(item));
            }
        });
        history.sort(Comparator.comparing(
                (FeedEntry e) -> e.createdAt() == null ? Instant.EPOCH : e.createdAt()).reversed());

        return new AccountView(accountId, posture, observations, history);
    }

    // ------------------------------------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------------------------------------

    private Decision parseDecision(DecisionItem item) {
        if (item.getResponseJson() == null || item.getResponseJson().isBlank()) {
            return null;
        }
        try {
            return jsonMapper.readValue(item.getResponseJson(), Decision.class);
        } catch (JacksonException ex) {
            LOG.warn("Could not parse stored decision for event {}: {}", item.getEventId(), ex.toString());
            return null;
        }
    }

    private static BigDecimal eurOf(long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2);
    }

    private static long p50(List<Long> latencies) {
        if (latencies.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(latencies);
        sorted.sort(Comparator.naturalOrder());
        return sorted.get((sorted.size() - 1) / 2);
    }
}
