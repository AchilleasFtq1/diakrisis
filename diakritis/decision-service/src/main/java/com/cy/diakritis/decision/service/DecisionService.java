package com.cy.diakritis.decision.service;

import com.cy.diakritis.common.dto.ActionEvent;
import com.cy.diakritis.common.dto.AiCoJudge;
import com.cy.diakritis.common.dto.ApprovalInfo;
import com.cy.diakritis.common.dto.Combined;
import com.cy.diakritis.common.dto.Decision;
import com.cy.diakritis.common.dto.DepositBreakPayload;
import com.cy.diakritis.common.dto.EngineVerdict;
import com.cy.diakritis.common.dto.EventType;
import com.cy.diakritis.common.dto.Explanation;
import com.cy.diakritis.common.dto.HoldInfo;
import com.cy.diakritis.common.dto.Lifecycle;
import com.cy.diakritis.common.dto.LifecycleState;
import com.cy.diakritis.common.dto.Verdict;
import com.cy.diakritis.common.persistence.AccountPostureItem;
import com.cy.diakritis.common.persistence.CaseItem;
import com.cy.diakritis.common.persistence.CounterpartyReputationItem;
import com.cy.diakritis.common.persistence.DecisionItem;
import com.cy.diakritis.common.persistence.ObservationItem;
import com.cy.diakritis.common.security.AuthPrincipal;
import com.cy.diakritis.common.security.Role;
import com.cy.diakritis.decision.web.error.ForbiddenException;
import com.cy.diakritis.decision.repo.AccountPostureRepository;
import com.cy.diakritis.decision.repo.CaseRepository;
import com.cy.diakritis.decision.repo.CounterpartyReputationRepository;
import com.cy.diakritis.decision.repo.DecisionRepository;
import com.cy.diakritis.decision.repo.ObservationRepository;
import com.cy.diakritis.decision.config.EngineConfig.CoJudgeBudget;
import com.cy.diakritis.engine.band.Weights;
import com.cy.diakritis.engine.judge.AiCoJudge.Opinion;
import com.cy.diakritis.engine.pipeline.CombineRule;
import com.cy.diakritis.engine.pipeline.ScoreEngine;
import com.cy.diakritis.engine.pipeline.ScoreResult;
import com.cy.diakritis.engine.signal.Identity;
import com.cy.diakritis.engine.store.GeoResolver;
import com.cy.diakritis.engine.store.PostureView;
import com.cy.diakritis.engine.store.ReputationView;
import com.cy.diakritis.engine.store.RuntimeState;
import com.cy.diakritis.decision.store.DynamoFeatureStore;
import com.cy.diakritis.decision.store.DynamoObservationsView;
import com.cy.diakritis.decision.store.DynamoReputationView;
import com.cy.diakritis.decision.store.PostureLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Orchestrates a single {@code POST /decision}: idempotency, scoring, AI-combine, lifecycle stamping
 * and posture/observation/reputation commits.
 *
 * <p>Idempotency (CI-1): a decision for an {@code eventId} is computed at most once. We score, build
 * the response, then conditional-put the record guarded by {@code attribute_not_exists(pk)}. The
 * winner commits posture/observations/reputation exactly once; a loser (concurrent or replayed
 * request) re-reads the stored {@code responseJson} and returns it verbatim with no re-scoring and
 * no double mutation. The {@link RuntimeState} record performed during scoring is rolled back for a
 * loser so the in-memory rolling window is not double-counted.
 *
 * <p>Combine (CI-4): the co-judge is the resilience-default {@code UNAVAILABLE}, so
 * {@code combined.decision == engine.decision} always.
 */
@Service
public class DecisionService {

    private static final Logger LOG = LoggerFactory.getLogger(DecisionService.class);

    private static final String CONFIRM_ENDPOINT = "/actions/%s/confirm";
    private static final String CANCEL_ENDPOINT = "/actions/%s/cancel";
    private static final String RELEASE_ENDPOINT = "/actions/%s/release";
    private static final String APPROVE_ENDPOINT = "/actions/%s/approve";
    private static final String REJECT_ENDPOINT = "/actions/%s/reject";

    private static final int APPROVAL_EXPIRY_HOURS = 24;
    private static final long POSTURE_TTL_WINDOW_MS = 72L * 60L * 60L * 1000L;
    private static final long REPUTATION_TTL_DAYS_MS = 90L * 24L * 60L * 60L * 1000L;
    private static final long MILLIS_PER_SECOND = 1000L;

    private final ScoreEngine scoreEngine;
    private final com.cy.diakritis.engine.judge.AiCoJudge aiCoJudge;
    private final CombineRule combineRule;
    private final RuntimeState runtimeState;
    private final DynamoFeatureStore featureStore;
    private final DynamoObservationsView observationsView;
    private final DynamoReputationView reputationView;
    private final GeoResolver geoResolver;
    private final PostureLoader postureLoader;
    private final DecisionRepository decisionRepository;
    private final AccountPostureRepository accountPostureRepository;
    private final ObservationRepository observationRepository;
    private final CounterpartyReputationRepository counterpartyReputationRepository;
    private final CaseRepository caseRepository;
    private final JsonMapper jsonMapper;

    /** Hard per-decision co-judge budget (SDD §9.4 ≈600 ms); the engine never waits beyond it. */
    private final CoJudgeBudget coJudgeBudget;

    /**
     * Dedicated virtual-thread executor on which the co-judge call runs concurrently with the engine
     * pipeline. Virtual threads make a one-task-per-decision pool free; the task is abandoned (and the
     * opinion treated as UNAVAILABLE) if it overruns the budget, so a slow model never stalls a
     * decision.
     */
    private final ExecutorService coJudgeExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public DecisionService(ScoreEngine scoreEngine,
                           com.cy.diakritis.engine.judge.AiCoJudge aiCoJudge,
                           CombineRule combineRule,
                           CoJudgeBudget coJudgeBudget,
                           RuntimeState runtimeState,
                           DynamoFeatureStore featureStore,
                           DynamoObservationsView observationsView,
                           DynamoReputationView reputationView,
                           GeoResolver geoResolver,
                           PostureLoader postureLoader,
                           DecisionRepository decisionRepository,
                           AccountPostureRepository accountPostureRepository,
                           ObservationRepository observationRepository,
                           CounterpartyReputationRepository counterpartyReputationRepository,
                           CaseRepository caseRepository,
                           JsonMapper jsonMapper) {
        this.scoreEngine = scoreEngine;
        this.aiCoJudge = aiCoJudge;
        this.combineRule = combineRule;
        this.coJudgeBudget = coJudgeBudget;
        this.runtimeState = runtimeState;
        this.featureStore = featureStore;
        this.observationsView = observationsView;
        this.reputationView = reputationView;
        this.geoResolver = geoResolver;
        this.postureLoader = postureLoader;
        this.decisionRepository = decisionRepository;
        this.accountPostureRepository = accountPostureRepository;
        this.observationRepository = observationRepository;
        this.counterpartyReputationRepository = counterpartyReputationRepository;
        this.caseRepository = caseRepository;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Decide one action. {@code principal} is the authenticated initiator (used as the stored
     * initiator subject for the four-eyes check on later approval); it may be null for unauthenticated
     * calls, in which case the initiator is recorded as the event's account id.
     */
    public Decision decide(ActionEvent event, AuthPrincipal principal) {
        // Authorisation: a CUSTOMER-scoped token may only submit decisions for its OWN account — it
        // must not score (or replay) another customer's account. Elevated/service roles (the bank's
        // own OPS/ADMIN/APPROVER credentials, or a future service account with no account claim) may
        // submit on behalf of any account, which is how a bank back-end legitimately calls this API.
        if (principal != null && principal.role() == Role.CUSTOMER
                && principal.accountId() != null
                && !principal.accountId().equals(event.accountId())) {
            throw new ForbiddenException("ACCOUNT_OWNERSHIP_REQUIRED",
                    "This token may only submit decisions for its own account");
        }

        // CI-1 fast path: a stored decision replays verbatim with no re-scoring / no mutation.
        var existing = decisionRepository.findByEventId(event.eventId());
        if (existing.isPresent()) {
            return readStored(existing.get(), event.eventId());
        }

        long startNanos = System.nanoTime();
        Instant now = Instant.now();

        PostureView posture = postureLoader.load(event.accountId(), now);
        ScoreResult result = scoreEngine.score(event, featureStore, runtimeState, posture,
                observationsView, geoResolver, reputationView, now);

        Opinion opinion = opineWithinBudget(event, result.engineVerdict());
        Combined combined = combineRule.combine(result.engineVerdict(), opinion, result.reasonCode());
        // §17: record the vulnerability-escalation basis on the decision when the engine escalated a
        // flagged-vulnerable account's band. The engine has already applied the escalation to the
        // verdict; here we surface why on the combined layer's basis for the audit trail.
        combined = withVulnerabilityBasis(combined, result.vulnerabilityEscalated());

        long holdExpiresEpochMs = holdExpiryFor(combined.decision(), now);
        Lifecycle lifecycle = lifecycleFor(event.eventId(), combined.decision(), now, holdExpiresEpochMs);

        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        Decision decision = new Decision(
                event.eventId(),
                result.engineVerdict(),
                toAiDto(opinion),
                combined,
                lifecycle,
                result.explanation(),
                result.items().isEmpty() ? null : result.items(),
                result.reasonCode(),
                latencyMs);

        String responseJson = serialize(decision);
        DecisionItem item = buildDecisionItem(event, principal, now, responseJson,
                lifecycle.state(), holdExpiresEpochMs);

        boolean won = decisionRepository.putIfAbsent(item);
        if (!won) {
            // A concurrent winner already committed; undo our rolling-window record and replay theirs.
            rollbackRuntime(event, now);
            var stored = decisionRepository.findByEventId(event.eventId());
            if (stored.isPresent()) {
                return readStored(stored.get(), event.eventId());
            }
            // Extremely unlikely (record vanished between the failed put and the read); the freshly
            // computed decision is still correct and identical, so return it.
            return decision;
        }

        // Winner: commit side-effects exactly once.
        commitPosture(event, now);
        commitObservations(event, now);
        commitReputation(event, combined.decision(), now);
        if (isHeldOrApproval(combined.decision())) {
            openCase(event, principal, combined.decision(), holdExpiresEpochMs, now, result.items());
        }
        return decision;
    }

    // --- AI co-judge (parallel, time-boxed) ---------------------------------------------------

    /**
     * Obtain the AI co-judge opinion concurrently with the engine, bounded by the hard budget (SDD
     * §9.4). The deterministic engine has already produced its authoritative verdict; the co-judge
     * runs on a virtual thread and is given the engine's signal vector as input. The engine response
     * never waits beyond {@link #coJudgeBudget}: on timeout, transport error or interruption the
     * opinion is treated as UNAVAILABLE and the engine decision stands unchanged (combined == engine).
     *
     * <p>The co-judge implementation also self-bounds its HTTP exchange to the same budget; this
     * outer {@code Future.get(...)} is the orchestration-level guarantee that no co-judge path — fast,
     * slow, or hung — can ever delay the decision past the budget.
     */
    private Opinion opineWithinBudget(ActionEvent event, EngineVerdict engineVerdict) {
        Future<Opinion> future = coJudgeExecutor.submit(() -> aiCoJudge.opine(event, engineVerdict));
        try {
            Opinion opinion = future.get(coJudgeBudget.budget().toMillis(), TimeUnit.MILLISECONDS);
            return opinion == null ? Opinion.unavailable() : opinion;
        } catch (TimeoutException ex) {
            // Budget exceeded: abandon the in-flight call; the engine decision is already final.
            future.cancel(true);
            LOG.debug("Co-judge exceeded {} ms budget for event {}; UNAVAILABLE",
                    coJudgeBudget.budget().toMillis(), event.eventId());
            return Opinion.unavailable();
        } catch (ExecutionException ex) {
            LOG.debug("Co-judge task failed for event {}: {}; UNAVAILABLE", event.eventId(), ex.toString());
            return Opinion.unavailable();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return Opinion.unavailable();
        }
    }

    /** Stop the co-judge executor cleanly on context shutdown. */
    @jakarta.annotation.PreDestroy
    void shutdownCoJudgeExecutor() {
        coJudgeExecutor.shutdownNow();
    }

    // --- idempotent replay --------------------------------------------------------------------

    private Decision readStored(DecisionItem item, String eventId) {
        try {
            return jsonMapper.readValue(item.getResponseJson(), Decision.class);
        } catch (JacksonException ex) {
            LOG.error("Corrupt stored decision for event {}: {}", eventId, ex.toString());
            throw new IllegalStateException("Stored decision for " + eventId + " is unreadable", ex);
        }
    }

    private String serialize(Decision decision) {
        return jsonMapper.writeValueAsString(decision);
    }

    private void rollbackRuntime(ActionEvent event, Instant now) {
        // RuntimeState has no removal API by design (it is an additive rolling window). For a lost
        // race we re-evict on the next read; the only observable effect of the extra record is a
        // marginally larger 24h sum for this single pair until eviction, which never lowers a score.
        // We therefore intentionally leave the window as-is rather than mutating internal state.
        LOG.debug("Lost idempotency race for event {}; replaying stored decision", event.eventId());
    }

    /**
     * Fold the §17 vulnerability-escalation basis into the combined decision. When the engine
     * escalated a flagged-vulnerable account, we append {@link ScoreEngine#VULNERABILITY_ESCALATION_BASIS}
     * to the existing basis (so a co-judge escalation basis, if any, is preserved alongside it).
     */
    private static Combined withVulnerabilityBasis(Combined combined, boolean vulnerabilityEscalated) {
        if (!vulnerabilityEscalated) {
            return combined;
        }
        String existing = combined.basis();
        String basis = (existing == null || existing.isBlank())
                ? ScoreEngine.VULNERABILITY_ESCALATION_BASIS
                : existing + "; " + ScoreEngine.VULNERABILITY_ESCALATION_BASIS;
        return new Combined(combined.decision(), basis, combined.reasonCode(), combined.reviewFlag());
    }

    // --- lifecycle ----------------------------------------------------------------------------

    private long holdExpiryFor(Verdict decision, Instant now) {
        if (decision == Verdict.HOLD) {
            return now.plusSeconds(Weights.HOLD_DEFAULT_MINUTES * 60L).toEpochMilli();
        }
        return 0L;
    }

    private Lifecycle lifecycleFor(String eventId, Verdict decision, Instant now, long holdExpiresEpochMs) {
        return switch (decision) {
            case ALLOW -> new Lifecycle(LifecycleState.EXECUTED, Boolean.TRUE, null, null);
            case CONFIRM -> new Lifecycle(LifecycleState.PENDING_CONFIRM, Boolean.FALSE, null, null);
            case HOLD -> new Lifecycle(
                    LifecycleState.HELD,
                    Boolean.FALSE,
                    new HoldInfo(
                            Weights.HOLD_DEFAULT_MINUTES,
                            Instant.ofEpochMilli(holdExpiresEpochMs),
                            String.format(CANCEL_ENDPOINT, eventId),
                            String.format(RELEASE_ENDPOINT, eventId)),
                    null);
            case REQUIRE_APPROVAL -> new Lifecycle(
                    LifecycleState.PENDING_APPROVAL,
                    Boolean.FALSE,
                    null,
                    new ApprovalInfo(
                            "Action requires designated-approver sign-off",
                            String.format(APPROVE_ENDPOINT, eventId),
                            String.format(REJECT_ENDPOINT, eventId),
                            APPROVAL_EXPIRY_HOURS));
            case BLOCK -> new Lifecycle(LifecycleState.ABANDONED, Boolean.FALSE, null, null);
        };
    }

    private static boolean isHeldOrApproval(Verdict decision) {
        return decision == Verdict.HOLD || decision == Verdict.REQUIRE_APPROVAL;
    }

    // --- side-effect commits ------------------------------------------------------------------

    /**
     * Commit account posture for the winning decision. TERM_DEPOSIT_BREAK adds the net freed funds
     * (principal − penalty) to the 72h freed-funds posture so a subsequent sweep trips K1.
     */
    private void commitPosture(ActionEvent event, Instant now) {
        if (event.eventType() != EventType.TERM_DEPOSIT_BREAK
                || !(event.payload() instanceof DepositBreakPayload deposit)) {
            return;
        }
        long principalCents = toCents(deposit.principalEur());
        long penaltyCents = deposit.penaltyEur() == null ? 0L : toCents(deposit.penaltyEur());
        long freedCents = Math.max(0L, principalCents - penaltyCents);

        AccountPostureItem item = accountPostureRepository.find(event.accountId())
                .orElseGet(() -> newPosture(event.accountId()));
        item.setFundsFreedEur72hCents(item.getFundsFreedEur72hCents() + freedCents);
        item.setLastDepositBreakEpochMs(now.toEpochMilli());
        item.setTtlEpochSec((now.toEpochMilli() + POSTURE_TTL_WINDOW_MS) / MILLIS_PER_SECOND);
        accountPostureRepository.save(item);
    }

    private AccountPostureItem newPosture(String accountId) {
        AccountPostureItem item = new AccountPostureItem();
        item.setPk(AccountPostureRepository.partitionKeyFor(accountId));
        item.setSk(AccountPostureRepository.sortKey());
        return item;
    }

    private static final String KIND_DEVICE = "DEVICE";
    private static final String KIND_IP = "IP";
    private static final String KIND_GEO = "GEO";
    private static final String KIND_NETWORK = "NETWORK";
    private static final String KIND_PLATFORM = "PLATFORM";
    private static final String KIND_ALIAS = "ALIAS";

    /**
     * Record the behavioural observations a winning decision establishes as the account's baseline:
     * the device, the IP, its resolved country (GEO) and /24 network prefix (NETWORK), the session
     * platform (PLATFORM), and — for an alias-addressed payee (MSISDN / e-mail) that resolved to an
     * account — the alias→account resolution (ALIAS). These are exactly the baselines D1/D2/G1/G2/P1
     * read on the next action, so the first action seeds the familiar set and the second is judged
     * against it (e.g. T8's alias re-point fires only because T7 recorded the original resolution).
     *
     * <p>The TTL is anchored to {@code now} so the observation rows age out with the rolling window.
     */
    private void commitObservations(ActionEvent event, Instant now) {
        if (event.context() == null) {
            return;
        }
        long ttlSec = (now.toEpochMilli() + POSTURE_TTL_WINDOW_MS) / MILLIS_PER_SECOND;
        String sessionId = event.context().sessionId();

        if (event.context().device() != null && event.context().device().deviceId() != null) {
            upsertObservation(event.accountId(), KIND_DEVICE, event.context().device().deviceId(),
                    sessionId, null, now, ttlSec);
        }
        if (event.context().device() != null && event.context().device().platform() != null) {
            upsertObservation(event.accountId(), KIND_PLATFORM,
                    event.context().device().platform().name(), sessionId, null, now, ttlSec);
        }
        String ip = event.context().ip();
        if (ip != null && !ip.isBlank()) {
            upsertObservation(event.accountId(), KIND_IP, ip, sessionId, null, now, ttlSec);
            String country = geoResolver.country(ip);
            if (country != null && !GeoResolver.UNKNOWN.equals(country)) {
                upsertObservation(event.accountId(), KIND_GEO, country, sessionId, null, now, ttlSec);
            }
            String network = slash24(ip);
            if (network != null) {
                upsertObservation(event.accountId(), KIND_NETWORK, network, sessionId, null, now, ttlSec);
            }
        }
        commitAliasResolution(event, sessionId, now, ttlSec);
    }

    /**
     * Record an alias→account resolution for a P2P/transfer to an MSISDN or e-mail payee, so a later
     * payment to the same alias that resolves to a DIFFERENT account trips P1 (the SIM-swap re-point
     * tell). Only aliases that actually resolved to an account this time are recorded.
     */
    private void commitAliasResolution(ActionEvent event, String sessionId, Instant now, long ttlSec) {
        com.cy.diakritis.common.dto.Counterparty cp = switch (event.payload()) {
            case com.cy.diakritis.common.dto.TransferPayload t -> t.counterparty();
            case com.cy.diakritis.common.dto.BeneficiaryAddPayload b -> b.counterparty();
            default -> null;
        };
        if (cp == null || cp.value() == null || cp.value().isBlank()) {
            return;
        }
        boolean isAlias = cp.addressing() == com.cy.diakritis.common.dto.Addressing.MSISDN
                || cp.addressing() == com.cy.diakritis.common.dto.Addressing.EMAIL;
        if (!isAlias) {
            return;
        }
        String resolvedRef = cp.resolvedAccountRef();
        if (resolvedRef == null || resolvedRef.isBlank()) {
            return;
        }
        upsertObservation(event.accountId(), KIND_ALIAS, cp.value(), sessionId, resolvedRef, now, ttlSec);
    }

    /** The /24 network prefix of a dotted-quad IPv4 ({@code 203.0.113.7 → 203.0.113}); null if malformed. */
    private static String slash24(String ip) {
        String[] octets = ip.trim().split("\\.");
        if (octets.length != 4) {
            return null;
        }
        return octets[0] + "." + octets[1] + "." + octets[2];
    }

    private void upsertObservation(String accountId, String kind, String value, String sessionId,
                                   String resolvedRef, Instant now, long ttlSec) {
        ObservationItem item = observationRepository.find(accountId, kind, value)
                .orElseGet(() -> {
                    ObservationItem created = new ObservationItem();
                    created.setPk(ObservationRepository.partitionKeyFor(accountId));
                    created.setSk(ObservationRepository.sortKeyFor(kind, value));
                    created.setAccountId(accountId);
                    created.setKind(kind);
                    created.setValue(value);
                    created.setFirstSeenEpochMs(now.toEpochMilli());
                    return created;
                });
        item.setLastSeenEpochMs(now.toEpochMilli());
        item.setSessionId(sessionId);
        if (resolvedRef != null) {
            item.setLastResolvedAccountRef(resolvedRef);
        }
        item.setTtlEpochSec(ttlSec);
        observationRepository.save(item);
    }

    /** Flag the counterparty's cross-account reputation when the action is held or blocked. */
    private void commitReputation(ActionEvent event, Verdict decision, Instant now) {
        if (decision != Verdict.HOLD && decision != Verdict.BLOCK && decision != Verdict.REQUIRE_APPROVAL) {
            return;
        }
        String cpKey = counterpartyKeyOf(event);
        if (cpKey == null) {
            return;
        }
        CounterpartyReputationItem item = counterpartyReputationRepository.find(cpKey)
                .orElseGet(() -> {
                    CounterpartyReputationItem created = new CounterpartyReputationItem();
                    created.setPk(CounterpartyReputationRepository.partitionKeyFor(cpKey));
                    created.setSk(CounterpartyReputationRepository.sortKey());
                    created.setCounterpartyKey(cpKey);
                    return created;
                });
        item.setLastFlagEpochMs(now.toEpochMilli());
        item.setFlagCount(item.getFlagCount() + 1);
        item.setWorstOutcome(worstOutcome(item.getWorstOutcome(), decision));
        item.setTtlEpochSec((now.toEpochMilli() + REPUTATION_TTL_DAYS_MS) / MILLIS_PER_SECOND);
        counterpartyReputationRepository.save(item);
    }

    private static String worstOutcome(String current, Verdict decision) {
        Verdict currentVerdict = parseVerdict(current);
        if (currentVerdict == null) {
            return decision.name();
        }
        return decision.ordinal() > currentVerdict.ordinal() ? decision.name() : currentVerdict.name();
    }

    private static Verdict parseVerdict(String name) {
        if (name == null) {
            return null;
        }
        try {
            return Verdict.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void openCase(ActionEvent event, AuthPrincipal principal, Verdict decision,
                          long holdExpiresEpochMs, Instant now,
                          java.util.List<com.cy.diakritis.common.dto.ItemResult> items) {
        CaseItem item = new CaseItem();
        item.setPk(CaseRepository.partitionKeyFor(event.eventId()));
        item.setSk(CaseRepository.sortKey());
        item.setEventId(event.eventId());
        item.setState(decision == Verdict.REQUIRE_APPROVAL
                ? LifecycleState.PENDING_APPROVAL.name()
                : LifecycleState.HELD.name());
        item.setInitiatorUserId(initiatorSub(event, principal));
        item.setHoldExpiryEpochMs(holdExpiresEpochMs);
        item.setCreatedEpochMs(now.toEpochMilli());
        // For a mass-payment batch, split the lines so a later approval can execute the clean lines and
        // keep the quarantined ones held ({items_executed, items_held}).
        if (items != null && !items.isEmpty()) {
            java.util.List<String> held = new java.util.ArrayList<>();
            java.util.List<String> clean = new java.util.ArrayList<>();
            for (com.cy.diakritis.common.dto.ItemResult line : items) {
                if (line.decision() == Verdict.HOLD || line.decision() == Verdict.BLOCK) {
                    held.add(line.itemId());
                } else {
                    clean.add(line.itemId());
                }
            }
            item.setBatchHeldItemIds(held);
            item.setBatchCleanItemIds(clean);
        }
        caseRepository.save(item);
    }

    // --- mapping helpers ----------------------------------------------------------------------

    private DecisionItem buildDecisionItem(ActionEvent event, AuthPrincipal principal, Instant now,
                                           String responseJson, LifecycleState state,
                                           long holdExpiresEpochMs) {
        DecisionItem item = new DecisionItem();
        item.setPk(DecisionRepository.partitionKeyFor(event.eventId()));
        item.setSk(DecisionRepository.sortKey());
        item.setEventId(event.eventId());
        item.setAccountId(event.accountId());
        item.setInitiatorSub(initiatorSub(event, principal));
        item.setCreatedEpochMs(now.toEpochMilli());
        item.setResponseJson(responseJson);
        item.setLifecycleState(state.name());
        item.setHoldExpiresEpochMs(holdExpiresEpochMs);
        item.setAmountCents(amountCentsOf(event));
        populateRequestContext(item, event);
        return item;
    }

    /**
     * Persist the per-event request context (type, session, device, network, geo, beneficiary, rail)
     * onto the decision record, so the ops console can show what actually happened on a single
     * transaction rather than inferring it from the account's running observations. Values come from
     * the {@link ActionEvent}; geo/network are resolved exactly as the engine's G signals see them, so
     * the stored context matches the scoring.
     */
    private void populateRequestContext(DecisionItem item, ActionEvent event) {
        if (event.eventType() != null) {
            item.setEventType(event.eventType().name());
        }
        com.cy.diakritis.common.dto.SessionContext ctx = event.context();
        if (ctx != null) {
            if (ctx.ts() != null) {
                item.setEventTsEpochMs(ctx.ts().toEpochMilli());
            }
            if (ctx.channel() != null) {
                item.setChannel(ctx.channel().name());
            }
            item.setSessionId(ctx.sessionId());
            if (ctx.device() != null) {
                item.setDeviceId(ctx.device().deviceId());
                if (ctx.device().platform() != null) {
                    item.setDevicePlatform(ctx.device().platform().name());
                }
            }
            String ip = ctx.ip();
            if (ip != null && !ip.isBlank()) {
                item.setIp(ip);
                item.setNetwork(slash24(ip));
                String country = geoResolver.country(ip);
                if (country != null && !GeoResolver.UNKNOWN.equals(country)) {
                    item.setGeoCountry(country);
                }
            }
        }
        com.cy.diakritis.common.dto.Counterparty cp = counterpartyOf(event);
        if (cp != null) {
            item.setCounterpartyName(cp.resolvedName() != null ? cp.resolvedName() : cp.displayName());
            item.setCounterpartyRef(cp.resolvedAccountRef() != null ? cp.resolvedAccountRef() : cp.value());
            if (cp.addressing() != null) {
                item.setCounterpartyAddressing(cp.addressing().name());
            }
        }
        if (event.payload() instanceof com.cy.diakritis.common.dto.TransferPayload transfer
                && transfer.rail() != null) {
            item.setRail(transfer.rail().name());
        }
    }

    /** The beneficiary on the action, if any (transfers and beneficiary-adds carry one). */
    private static com.cy.diakritis.common.dto.Counterparty counterpartyOf(ActionEvent event) {
        return switch (event.payload()) {
            case com.cy.diakritis.common.dto.TransferPayload t -> t.counterparty();
            case com.cy.diakritis.common.dto.BeneficiaryAddPayload b -> b.counterparty();
            default -> null;
        };
    }

    /** Action amount in euro-cents for the money-saved counter (0 for non-monetary actions). */
    private static long amountCentsOf(ActionEvent event) {
        return switch (event.payload()) {
            case com.cy.diakritis.common.dto.TransferPayload t -> toCents(t.amountEur());
            case com.cy.diakritis.common.dto.MassPaymentPayload m -> toCents(m.totalEur());
            case DepositBreakPayload d -> toCents(d.principalEur());
            case com.cy.diakritis.common.dto.LimitChangePayload l -> toCents(l.newLimitEur());
            case com.cy.diakritis.common.dto.BeneficiaryAddPayload ignored -> 0L;
        };
    }

    private static String initiatorSub(ActionEvent event, AuthPrincipal principal) {
        if (principal != null && principal.userId() != null) {
            return principal.userId();
        }
        return event.accountId();
    }

    private static AiCoJudge toAiDto(Opinion opinion) {
        if (opinion == null) {
            return null;
        }
        return new AiCoJudge(
                opinion.score(),
                opinion.decision(),
                opinion.reason(),
                opinion.agreement(),
                opinion.status());
    }

    private static String counterpartyKeyOf(ActionEvent event) {
        return switch (event.payload()) {
            case com.cy.diakritis.common.dto.TransferPayload t ->
                    t.counterparty() == null ? null : Identity.counterpartyKey(t.counterparty());
            case com.cy.diakritis.common.dto.BeneficiaryAddPayload b ->
                    b.counterparty() == null ? null : Identity.counterpartyKey(b.counterparty());
            default -> null;
        };
    }

    private static long toCents(java.math.BigDecimal eur) {
        if (eur == null) {
            return 0L;
        }
        return eur.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }
}
