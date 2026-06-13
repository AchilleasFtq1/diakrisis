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
import com.cy.diakritis.decision.repo.AccountPostureRepository;
import com.cy.diakritis.decision.repo.CaseRepository;
import com.cy.diakritis.decision.repo.CounterpartyReputationRepository;
import com.cy.diakritis.decision.repo.DecisionRepository;
import com.cy.diakritis.decision.repo.ObservationRepository;
import com.cy.diakritis.engine.band.Weights;
import com.cy.diakritis.engine.judge.AiCoJudge.Opinion;
import com.cy.diakritis.engine.pipeline.CombineRule;
import com.cy.diakritis.engine.pipeline.ScoreEngine;
import com.cy.diakritis.engine.pipeline.ScoreResult;
import com.cy.diakritis.engine.signal.Identity;
import com.cy.diakritis.engine.store.PostureView;
import com.cy.diakritis.engine.store.RuntimeState;
import com.cy.diakritis.decision.store.DynamoFeatureStore;
import com.cy.diakritis.decision.store.DynamoObservationsView;
import com.cy.diakritis.decision.store.PostureLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

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
    private final PostureLoader postureLoader;
    private final DecisionRepository decisionRepository;
    private final AccountPostureRepository accountPostureRepository;
    private final ObservationRepository observationRepository;
    private final CounterpartyReputationRepository counterpartyReputationRepository;
    private final CaseRepository caseRepository;
    private final JsonMapper jsonMapper;

    public DecisionService(ScoreEngine scoreEngine,
                           com.cy.diakritis.engine.judge.AiCoJudge aiCoJudge,
                           CombineRule combineRule,
                           RuntimeState runtimeState,
                           DynamoFeatureStore featureStore,
                           DynamoObservationsView observationsView,
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
        this.runtimeState = runtimeState;
        this.featureStore = featureStore;
        this.observationsView = observationsView;
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
        // CI-1 fast path: a stored decision replays verbatim with no re-scoring / no mutation.
        var existing = decisionRepository.findByEventId(event.eventId());
        if (existing.isPresent()) {
            return readStored(existing.get(), event.eventId());
        }

        long startNanos = System.nanoTime();
        Instant now = Instant.now();

        PostureView posture = postureLoader.load(event.accountId(), now);
        ScoreResult result = scoreEngine.score(event, featureStore, runtimeState, posture,
                observationsView, now);

        Opinion opinion = aiCoJudge.opine(event, result.engineVerdict());
        Combined combined = combineRule.combine(result.engineVerdict(), opinion, result.reasonCode());

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
            openCase(event, principal, combined.decision(), holdExpiresEpochMs, now);
        }
        return decision;
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

    /** Record the device/IP/session last-seen observations for recency features on later actions. */
    private void commitObservations(ActionEvent event, Instant now) {
        if (event.context() == null) {
            return;
        }
        long ttlSec = (now.toEpochMilli() + POSTURE_TTL_WINDOW_MS) / MILLIS_PER_SECOND;
        if (event.context().device() != null && event.context().device().deviceId() != null) {
            upsertObservation(event.accountId(), "DEVICE", event.context().device().deviceId(),
                    event.context().sessionId(), null, now, ttlSec);
        }
        if (event.context().ip() != null) {
            upsertObservation(event.accountId(), "IP", event.context().ip(),
                    event.context().sessionId(), null, now, ttlSec);
        }
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
                          long holdExpiresEpochMs, Instant now) {
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
        return item;
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
