package com.cy.diakritis.ops.service;

import com.cy.diakritis.common.dto.Decision;
import com.cy.diakritis.common.dto.LifecycleState;
import com.cy.diakritis.common.dto.Outcome;
import com.cy.diakritis.common.persistence.AccountPostureItem;
import com.cy.diakritis.common.persistence.CaseItem;
import com.cy.diakritis.common.persistence.CounterpartyBaselineItem;
import com.cy.diakritis.common.persistence.CounterpartyReputationItem;
import com.cy.diakritis.common.persistence.DecisionItem;
import com.cy.diakritis.common.persistence.ObservationItem;
import com.cy.diakritis.common.persistence.OutcomeItem;
import com.cy.diakritis.ops.web.dto.AccountView;
import com.cy.diakritis.ops.web.dto.ApprovalEntry;
import com.cy.diakritis.ops.web.dto.CountersView;
import com.cy.diakritis.ops.web.dto.CounterpartyView;
import com.cy.diakritis.ops.web.dto.FeedEntry;
import com.cy.diakritis.ops.web.dto.OutcomeView;
import com.cy.diakritis.ops.web.dto.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /** Recent decisions surfaced in the account-view timeline (the kill-chain hero). The full, paged
     *  history is served separately by {@link #accountHistory}. */
    private static final int ACCOUNT_TIMELINE_LIMIT = 12;
    /** Pulls the event type out of the audit explanation ("decision=… type=TRANSFER typologies=…"). */
    private static final Pattern EVENT_TYPE = Pattern.compile("type=([A-Z_]+)");

    private final DynamoDbTable<DecisionItem> decisionTable;
    private final DynamoDbTable<CaseItem> caseTable;
    private final DynamoDbTable<OutcomeItem> outcomeTable;
    private final DynamoDbTable<AccountPostureItem> postureTable;
    private final DynamoDbTable<ObservationItem> observationTable;
    private final DynamoDbTable<CounterpartyReputationItem> reputationTable;
    private final DynamoDbTable<CounterpartyBaselineItem> baselineTable;
    private final JsonMapper jsonMapper;

    public OpsService(DynamoDbTable<DecisionItem> decisionTable, DynamoDbTable<CaseItem> caseTable,
                      DynamoDbTable<OutcomeItem> outcomeTable, DynamoDbTable<AccountPostureItem> postureTable,
                      DynamoDbTable<ObservationItem> observationTable,
                      DynamoDbTable<CounterpartyReputationItem> reputationTable,
                      DynamoDbTable<CounterpartyBaselineItem> baselineTable, JsonMapper jsonMapper) {
        this.decisionTable = decisionTable;
        this.caseTable = caseTable;
        this.outcomeTable = outcomeTable;
        this.postureTable = postureTable;
        this.observationTable = observationTable;
        this.reputationTable = reputationTable;
        this.baselineTable = baselineTable;
        this.jsonMapper = jsonMapper;
    }

    // ------------------------------------------------------------------------------------------------
    // Feed.
    // ------------------------------------------------------------------------------------------------

    /**
     * Server-paged decision feed. Filtering (by outcome and free-text) happens before paging so the
     * page total reflects the filtered set. Built over the {@value #FEED_LIMIT} most-recent decisions.
     *
     * @param page     1-based page index (clamped)
     * @param size     page size (clamped to 1..{@link Page#MAX_SIZE})
     * @param outcomes if non-empty, keep only these verdicts (ALLOW/CONFIRM/REQUIRE_APPROVAL/HOLD/BLOCK)
     * @param query    if non-blank, case-insensitive substring over account/type/typology/reason/initiator
     */
    public Page<FeedEntry> feed(int page, int size, List<String> outcomes, String query) {
        List<FeedEntry> filtered = recentFeed().stream()
                .filter(e -> outcomes == null || outcomes.isEmpty()
                        || (e.verdict() != null && outcomes.contains(e.verdict())))
                .filter(e -> matchesQuery(e, query))
                .toList();
        return Page.of(filtered, page, size);
    }

    /** The {@value #FEED_LIMIT} most-recent decisions, newest first — the basis for the feed and filters. */
    private List<FeedEntry> recentFeed() {
        List<FeedEntry> entries = new ArrayList<>();
        decisionTable.scan().items().forEach(item -> entries.add(toFeedEntry(item)));
        entries.sort(Comparator.comparing(
                (FeedEntry entry) -> entry.createdAt() == null ? Instant.EPOCH : entry.createdAt())
                .reversed());
        return entries.size() > FEED_LIMIT ? new ArrayList<>(entries.subList(0, FEED_LIMIT)) : entries;
    }

    private static boolean matchesQuery(FeedEntry e, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        StringBuilder hay = new StringBuilder();
        appendIfPresent(hay, e.accountId());
        appendIfPresent(hay, e.eventType());
        appendIfPresent(hay, e.reasonCode());
        appendIfPresent(hay, e.initiatorSub());
        if (e.typologies() != null) {
            e.typologies().forEach(t -> appendIfPresent(hay, t));
        }
        return hay.toString().toLowerCase().contains(query.toLowerCase());
    }

    private static void appendIfPresent(StringBuilder sb, String value) {
        if (value != null) {
            sb.append(value).append(' ');
        }
    }

    private FeedEntry toFeedEntry(DecisionItem item) {
        ParsedDecision parsed = parseDecisionResult(item);
        Decision decision = parsed.decision();
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
        }
        // Prefer the persisted event type; fall back to recovering it from the audit text for decisions
        // written before the request context was persisted.
        eventType = item.getEventType() != null ? item.getEventType()
                : (decision != null ? eventTypeFrom(decision) : null);
        return new FeedEntry(
                item.getEventId(),
                item.getAccountId(),
                item.getInitiatorSub(),
                item.getLifecycleState(),
                item.getCreatedEpochMs() > 0 ? Instant.ofEpochMilli(item.getCreatedEpochMs()) : null,
                item.getHoldExpiresEpochMs() > 0 ? Instant.ofEpochMilli(item.getHoldExpiresEpochMs()) : null,
                verdict, score, typologies, reasonCode, friction, eurOf(item.getAmountCents()), eventType,
                parsed.parseFailed());
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

    /**
     * Server-paged four-eyes queue. Filtering (by initiator and free-text) happens before paging.
     *
     * @param page      1-based page index (clamped)
     * @param size      page size (clamped to 1..{@link Page#MAX_SIZE})
     * @param query     if non-blank, case-insensitive substring over event/initiator/state
     * @param initiator if non-blank, keep only actions raised by this user (the "initiated by me" filter)
     */
    public Page<ApprovalEntry> approvals(int page, int size, String query, String initiator) {
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

        List<ApprovalEntry> filtered = entries.stream()
                .filter(a -> initiator == null || initiator.isBlank() || initiator.equals(a.initiatorUserId()))
                .filter(a -> approvalMatchesQuery(a, query))
                .toList();
        return Page.of(filtered, page, size);
    }

    private static boolean approvalMatchesQuery(ApprovalEntry a, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        StringBuilder hay = new StringBuilder();
        appendIfPresent(hay, a.eventId());
        appendIfPresent(hay, a.initiatorUserId());
        appendIfPresent(hay, a.state());
        return hay.toString().toLowerCase().contains(query.toLowerCase());
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

    /**
     * The full stored decision for one event (the verbatim response, incl. the signal breakdown),
     * enriched with the {@code Decisions}-table metadata (account, amount, type, timestamps, lifecycle)
     * so the detail page is self-contained and does not have to find the event in the paged feed.
     */
    public JsonNode decision(String eventId) {
        for (DecisionItem item : decisionTable.scan().items()) {
            if (eventId.equals(item.getEventId()) && item.getResponseJson() != null) {
                try {
                    JsonNode tree = jsonMapper.readTree(item.getResponseJson());
                    if (tree instanceof ObjectNode node) {
                        node.put("account_id", item.getAccountId());
                        node.put("amount_eur", eurOf(item.getAmountCents()));
                        node.put("initiator_sub", item.getInitiatorSub());
                        node.put("lifecycle_state", item.getLifecycleState());
                        if (item.getCreatedEpochMs() > 0) {
                            node.put("created_at", Instant.ofEpochMilli(item.getCreatedEpochMs()).toString());
                        }
                        String eventType = item.getEventType();
                        if (eventType == null) {
                            Decision parsed = parseDecision(item);
                            eventType = parsed == null ? null : eventTypeFrom(parsed);
                        }
                        if (eventType != null) {
                            node.put("event_type", eventType);
                        }
                        // Per-event request context (persisted at decision time). Null fields are simply
                        // omitted — the console flags them — so older decisions degrade gracefully.
                        putIfPresent(node, "channel", item.getChannel());
                        putIfPresent(node, "ip", item.getIp());
                        putIfPresent(node, "network", item.getNetwork());
                        putIfPresent(node, "geo_country", item.getGeoCountry());
                        putIfPresent(node, "device_id", item.getDeviceId());
                        putIfPresent(node, "device_platform", item.getDevicePlatform());
                        putIfPresent(node, "session_id", item.getSessionId());
                        putIfPresent(node, "rail", item.getRail());
                        putIfPresent(node, "counterparty_name", item.getCounterpartyName());
                        putIfPresent(node, "counterparty_ref", item.getCounterpartyRef());
                        putIfPresent(node, "counterparty_addressing", item.getCounterpartyAddressing());
                        if (item.getEventTsEpochMs() > 0) {
                            node.put("event_ts", Instant.ofEpochMilli(item.getEventTsEpochMs()).toString());
                        }
                        if (item.getHoldExpiresEpochMs() > 0) {
                            node.put("hold_expires_at", Instant.ofEpochMilli(item.getHoldExpiresEpochMs()).toString());
                        }
                    }
                    return tree;
                } catch (JacksonException ex) {
                    // Same corrupt/schema-incompatible stored decision condition as parseDecisionResult;
                    // log at ERROR (matching DecisionService.readStored) so a systematic break is
                    // alertable. The detail endpoint still surfaces this as a 404 to the caller.
                    LOG.error("Corrupt stored decision for event {}: {}", eventId, ex.toString());
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

        // Only the recent window goes in the account view (drives the timeline hero); the full history
        // is paged via accountHistory so a busy account doesn't ship its entire ledger in one payload.
        List<FeedEntry> all = accountHistoryAll(accountId);
        List<FeedEntry> timeline = all.size() > ACCOUNT_TIMELINE_LIMIT
                ? new ArrayList<>(all.subList(0, ACCOUNT_TIMELINE_LIMIT))
                : all;

        return new AccountView(accountId, posture, observations, timeline);
    }

    /** Server-paged full decision history for one account (newest first), backing the history table. */
    public Page<FeedEntry> accountHistory(String accountId, int page, int size) {
        return Page.of(accountHistoryAll(accountId), page, size);
    }

    private List<FeedEntry> accountHistoryAll(String accountId) {
        List<FeedEntry> history = new ArrayList<>();
        decisionTable.scan().items().forEach(item -> {
            if (accountId.equals(item.getAccountId())) {
                history.add(toFeedEntry(item));
            }
        });
        history.sort(Comparator.comparing(
                (FeedEntry e) -> e.createdAt() == null ? Instant.EPOCH : e.createdAt()).reversed());
        return history;
    }

    // ------------------------------------------------------------------------------------------------
    // Counterparty (mule) intelligence.
    // ------------------------------------------------------------------------------------------------

    /**
     * Flagged beneficiaries, most-flagged first. The reputation rows carry the flag history; the
     * per-account baseline rows are joined in to recover the name/IBAN and the <em>fan-in</em> (distinct
     * accounts that have paid the counterparty) — the signature of a mule collecting from many victims.
     */
    public Page<CounterpartyView> counterparties(int page, int size, String query) {
        Map<String, Set<String>> accountsByKey = new HashMap<>();
        Map<String, String> nameByKey = new HashMap<>();
        Map<String, String> ibanByKey = new HashMap<>();
        Map<String, long[]> payAggByKey = new HashMap<>(); // [payCount sum, max mean cents]
        for (CounterpartyBaselineItem b : baselineTable.scan().items()) {
            String key = b.getCounterpartyKey();
            if (key == null) {
                continue;
            }
            if (b.getAccountId() != null) {
                accountsByKey.computeIfAbsent(key, k -> new HashSet<>()).add(b.getAccountId());
            }
            if (b.getResolvedName() != null) {
                nameByKey.putIfAbsent(key, b.getResolvedName());
            }
            if (b.getCounterpartyIban() != null) {
                ibanByKey.putIfAbsent(key, b.getCounterpartyIban());
            }
            payAggByKey.merge(key, new long[]{b.getPayCount(), b.getMeanAmountCents()},
                    (a, c) -> new long[]{a[0] + c[0], Math.max(a[1], c[1])});
        }

        List<CounterpartyView> all = new ArrayList<>();
        for (CounterpartyReputationItem r : reputationTable.scan().items()) {
            String key = r.getCounterpartyKey();
            if (key == null) {
                continue;
            }
            long[] agg = payAggByKey.get(key);
            all.add(new CounterpartyView(
                    key,
                    nameByKey.get(key),
                    ibanByKey.get(key),
                    r.getWorstOutcome(),
                    r.getFlagCount(),
                    r.getLastFlagEpochMs() > 0 ? Instant.ofEpochMilli(r.getLastFlagEpochMs()) : null,
                    accountsByKey.getOrDefault(key, Set.of()).size(),
                    agg == null ? 0 : agg[0],
                    agg == null ? null : eurOf(agg[1])));
        }
        all.sort(Comparator.comparingLong(CounterpartyView::flagCount).reversed()
                .thenComparing(Comparator.comparingInt(CounterpartyView::fanInAccounts).reversed()));

        List<CounterpartyView> filtered = all.stream()
                .filter(c -> counterpartyMatches(c, query))
                .toList();
        return Page.of(filtered, page, size);
    }

    private static boolean counterpartyMatches(CounterpartyView c, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        StringBuilder hay = new StringBuilder();
        appendIfPresent(hay, c.counterpartyKey());
        appendIfPresent(hay, c.name());
        appendIfPresent(hay, c.iban());
        appendIfPresent(hay, c.worstOutcome());
        return hay.toString().toLowerCase().contains(query.toLowerCase());
    }

    // ------------------------------------------------------------------------------------------------
    // Outcomes ("wins") board.
    // ------------------------------------------------------------------------------------------------

    /**
     * Recorded outcomes, newest first — the money-protected catches and the false positives behind the
     * counters. Optionally filtered to one outcome type (CONFIRMED_SAVE / FALSE_POSITIVE).
     */
    public Page<OutcomeView> outcomes(int page, int size, String type) {
        List<OutcomeView> all = new ArrayList<>();
        for (OutcomeItem o : outcomeTable.scan().items()) {
            if (type != null && !type.isBlank() && !type.equalsIgnoreCase(o.getOutcome())) {
                continue;
            }
            all.add(new OutcomeView(
                    o.getEventId(),
                    o.getAccountId(),
                    o.getOutcome(),
                    eurOf(o.getAmountCents()),
                    o.getSignalPattern(),
                    o.getEpochMs() > 0 ? Instant.ofEpochMilli(o.getEpochMs()) : null));
        }
        all.sort(Comparator.comparing((OutcomeView v) -> v.at() == null ? Instant.EPOCH : v.at()).reversed());
        return Page.of(all, page, size);
    }

    // ------------------------------------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------------------------------------

    /**
     * Outcome of attempting to read a stored decision: the parsed {@link Decision} (null if it could not
     * be read) and whether the null is due to a parse failure of a non-blank stored response (as opposed
     * to there genuinely being no stored response). The latter distinction lets the feed surface a
     * corrupt/schema-incompatible decision as an explicit error rather than a benign engine-less row.
     */
    private record ParsedDecision(Decision decision, boolean parseFailed) {
        static final ParsedDecision ABSENT = new ParsedDecision(null, false);
    }

    private Decision parseDecision(DecisionItem item) {
        return parseDecisionResult(item).decision();
    }

    private ParsedDecision parseDecisionResult(DecisionItem item) {
        if (item.getResponseJson() == null || item.getResponseJson().isBlank()) {
            return ParsedDecision.ABSENT;
        }
        try {
            return new ParsedDecision(jsonMapper.readValue(item.getResponseJson(), Decision.class), false);
        } catch (JacksonException ex) {
            // A non-blank response that fails to parse is a corrupt/schema-incompatible stored decision,
            // not a legitimately engine-less row. Log at ERROR (matching DecisionService.readStored) so a
            // systematic break is alertable, and flag it so the console renders an explicit error badge.
            LOG.error("Corrupt stored decision for event {}: {}", item.getEventId(), ex.toString());
            return new ParsedDecision(null, true);
        }
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
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
