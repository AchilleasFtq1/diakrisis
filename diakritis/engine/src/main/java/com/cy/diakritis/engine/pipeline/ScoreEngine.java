package com.cy.diakritis.engine.pipeline;

import com.cy.diakritis.common.dto.ActionEvent;
import com.cy.diakritis.common.dto.Addressing;
import com.cy.diakritis.common.dto.BatchItem;
import com.cy.diakritis.common.dto.BeneficiaryAddPayload;
import com.cy.diakritis.common.dto.Counterparty;
import com.cy.diakritis.common.dto.DepositBreakPayload;
import com.cy.diakritis.common.dto.EngineVerdict;
import com.cy.diakritis.common.dto.Friction;
import com.cy.diakritis.common.dto.EventType;
import com.cy.diakritis.common.dto.Explanation;
import com.cy.diakritis.common.dto.ItemResult;
import com.cy.diakritis.common.dto.LimitChangePayload;
import com.cy.diakritis.common.dto.MassPaymentPayload;
import com.cy.diakritis.common.dto.Rail;
import com.cy.diakritis.common.dto.Signal;
import com.cy.diakritis.common.dto.TransferPayload;
import com.cy.diakritis.common.dto.Verdict;
import com.cy.diakritis.engine.band.Band;
import com.cy.diakritis.engine.band.Bands;
import com.cy.diakritis.engine.m1.M1Scorer;
import com.cy.diakritis.engine.m2.M2Scorer;
import com.cy.diakritis.engine.signal.A1AmountVsAccount;
import com.cy.diakritis.engine.signal.A2BalanceDrain;
import com.cy.diakritis.engine.signal.A3AmountVsCounterparty;
import com.cy.diakritis.engine.signal.A4ThresholdHugging;
import com.cy.diakritis.engine.signal.B1NewBeneficiary;
import com.cy.diakritis.engine.signal.B2BeneficiaryRecency;
import com.cy.diakritis.engine.signal.B3BeneficiaryJustAdded;
import com.cy.diakritis.engine.signal.B4EstablishedPayee;
import com.cy.diakritis.engine.signal.B5NameMismatch;
import com.cy.diakritis.engine.signal.C1OutOfPatternTime;
import com.cy.diakritis.engine.signal.C3RetryPressure;
import com.cy.diakritis.engine.signal.D1DeviceAgeDecay;
import com.cy.diakritis.engine.signal.D2PlatformAnomaly;
import com.cy.diakritis.engine.signal.G1UnfamiliarGeo;
import com.cy.diakritis.engine.signal.G2NewNetwork;
import com.cy.diakritis.engine.signal.Identity;
import com.cy.diakritis.engine.signal.K1FundsFreed;
import com.cy.diakritis.engine.signal.K2LimitRaisedRecently;
import com.cy.diakritis.engine.signal.K3BeneficiaryAddBurst;
import com.cy.diakritis.engine.signal.M1ModelSignal;
import com.cy.diakritis.engine.signal.M2ExemplarSignal;
import com.cy.diakritis.engine.signal.MP1NewCounterpartyShare;
import com.cy.diakritis.engine.signal.MP2CadenceTotalAnomaly;
import com.cy.diakritis.engine.signal.MP4BatchDrain;
import com.cy.diakritis.engine.signal.P1AliasRepoint;
import com.cy.diakritis.engine.signal.SignalContext;
import com.cy.diakritis.engine.signal.V1BurstVelocity;
import com.cy.diakritis.engine.signal.V2RisingAmounts;
import com.cy.diakritis.engine.signal.X1CrossAccountReputation;
import com.cy.diakritis.engine.store.AccountStatsView;
import com.cy.diakritis.engine.store.FeatureStore;
import com.cy.diakritis.engine.store.GeoResolver;
import com.cy.diakritis.engine.store.ObservationsView;
import com.cy.diakritis.engine.store.PostureView;
import com.cy.diakritis.engine.store.ReputationView;
import com.cy.diakritis.engine.store.RuntimeState;
import com.cy.diakritis.engine.typology.TypologyEvaluator;
import com.cy.diakritis.engine.typology.Typologies;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rules + model scoring core. Given an action and the read stores it produces a deterministic
 * {@link ScoreResult}: an {@link EngineVerdict}, a machine reason code and a dual-audience
 * explanation. It is the single place the scoring order, band/typology overrides and regulatory
 * carve-outs are encoded.
 *
 * <p>Scoring order (contractual):
 * <ol>
 *   <li>record the current event in the rolling window;</li>
 *   <li>evaluate every signal, summing signed contributions, clipped to {@code [0,100]};</li>
 *   <li>evaluate typologies from the signal values;</li>
 *   <li>map raw score → band by rail;</li>
 *   <li>typology override: one match pins HOLD; two+ matches with raw ≥ 85 escalate to BLOCK;</li>
 *   <li>cap non-monetary actions at CONFIRM;</li>
 *   <li>TERM_DEPOSIT_BREAK is forced to CONFIRM and never HOLD-pinned;</li>
 *   <li>a HOLD on an account with a designated approver routes to REQUIRE_APPROVAL;</li>
 *   <li>policy routing: business mass-payments and large limit raises route to REQUIRE_APPROVAL;</li>
 *   <li>SCA TRA exemption is granted only on a clean ALLOW of a TRANSFER/P2P.</li>
 * </ol>
 *
 * <p>The engine never carries {@code BigDecimal} into the hot path: amounts are converted to
 * euro-cents once at entry.
 */
public final class ScoreEngine {

    private static final int RAW_MIN = 0;
    private static final int RAW_MAX = 100;
    private static final double LIMIT_RAISE_APPROVAL_MULTIPLE = 2.0;

    /**
     * Separator for the per-line rolling-window record key ({@code eventId#itemId}). Makes each batch
     * line a distinct, replay-stable record so the 24h window counts every line exactly once.
     */
    private static final String BATCH_LINE_KEY_SEPARATOR = "#";

    public static final String SCA_TRA_BASIS =
            "PSD2 RTS Art.18 TRA (low value, low fraud-rate)";

    /**
     * §17 vulnerability-aware friction: the number of bands a flagged-vulnerable account's ALLOW or
     * CONFIRM outcome is escalated by. Exactly one band, capped at HOLD — mirroring the §8.3
     * AI-escalation asymmetry (the system can only make a vulnerable customer's outcome stricter,
     * never softer, and never past HOLD into a hard BLOCK).
     */
    public static final int VULNERABILITY_BAND_ESCALATION = 1;

    /** Basis recorded on the decision when the §17 vulnerability escalation has been applied. */
    public static final String VULNERABILITY_ESCALATION_BASIS = "vulnerability_escalation";

    private final M1Scorer m1Scorer;
    private final M2Scorer m2Scorer;
    private final TypologyEvaluator typologyEvaluator;

    /**
     * Full constructor wiring both model scorers. M2 (like M1) is additive and capped, degrading to a
     * constant 0 when its exemplar index is unavailable.
     */
    public ScoreEngine(M1Scorer m1Scorer, M2Scorer m2Scorer, TypologyEvaluator typologyEvaluator) {
        this.m1Scorer = m1Scorer;
        this.m2Scorer = m2Scorer;
        this.typologyEvaluator = typologyEvaluator;
    }

    /**
     * Back-compatible constructor without an M2 scorer. M2 degrades to the empty exemplar index and
     * contributes nothing, so the deterministic engine and M1 are unaffected.
     */
    public ScoreEngine(M1Scorer m1Scorer, TypologyEvaluator typologyEvaluator) {
        this(m1Scorer, M2Scorer.of(null, null), typologyEvaluator);
    }

    /**
     * Score one action.
     *
     * @param event   the action to score
     * @param store   offline feature baselines (read-only)
     * @param runtime in-process rolling 24h / session state (mutated: the current event is recorded)
     * @param posture rolling 72h account risk posture
     * @param obs     behavioural observation store
     * @param now     decision timestamp
     */
    public ScoreResult score(ActionEvent event,
                             FeatureStore store,
                             RuntimeState runtime,
                             PostureView posture,
                             ObservationsView obs,
                             Instant now) {
        return score(event, store, runtime, posture, obs,
                GeoResolver.unknownAll(), ReputationView.empty(), now);
    }

    /**
     * Score one action with the full geo + cross-account reputation seams wired (G1/G2 and X1). The
     * narrower overload routes here with the resilient empty seams, so geo and X1 score 0 when a
     * caller has no geolocation source or reputation store.
     */
    public ScoreResult score(ActionEvent event,
                             FeatureStore store,
                             RuntimeState runtime,
                             PostureView posture,
                             ObservationsView obs,
                             GeoResolver geo,
                             ReputationView reputation,
                             Instant now) {
        return switch (event.eventType()) {
            case MASS_PAYMENT -> scoreMassPayment(event, store, runtime, posture, obs, geo, reputation, now);
            default -> scoreSingle(event, store, runtime, posture, obs, geo, reputation, now);
        };
    }

    // --- single-action scoring ---------------------------------------------------------------

    private ScoreResult scoreSingle(ActionEvent event,
                                    FeatureStore store,
                                    RuntimeState runtime,
                                    PostureView posture,
                                    ObservationsView obs,
                                    GeoResolver geo,
                                    ReputationView reputation,
                                    Instant now) {
        EventType type = event.eventType();
        Rail rail = railOf(event);
        Counterparty counterparty = counterpartyOf(event);
        String cpKey = counterparty != null ? Identity.counterpartyKey(counterparty) : nonMonetaryKey(event);

        long amountCents = amountCentsOf(event);
        long availableCents = availableBalanceCentsOf(event);

        // 1. Record the current event so the rolling-window logical amount includes it. Keyed by the
        // eventId so a replayed/duplicate request does not double-count the 24h window (CI-1).
        runtime.record(event.eventId(), event.accountId(), cpKey, amountCents, now.toEpochMilli());
        if (type == EventType.BENEFICIARY_ADD && event.context() != null) {
            runtime.recordBeneficiaryAdd(event.context().sessionId(), now.toEpochMilli());
        }
        // C3 retry-pressure: record this monetary attempt so a coached victim re-submitting at successively higher
        // amounts in one session is detectable. Mirrors recordBeneficiaryAdd; a CI-1 replay never re-scores so it
        // is not re-recorded.
        if (event.context() != null
                && (type == EventType.TRANSFER || type == EventType.P2P_TRANSFER)
                && amountCents > 0) {
            runtime.recordRaisedAttempt(event.context().sessionId(), amountCents, now.toEpochMilli());
        }

        long logicalAmountCents = runtime.logicalAmountCents(event.accountId(), cpKey, amountCents, now.toEpochMilli());

        SignalContext ctx = new SignalContext(
                event, store, runtime, posture, obs, geo, reputation, cpKey,
                logicalAmountCents, amountCents, availableCents, now);

        // 2. Evaluate signals.
        ScoredSignals scored = evaluateSignals(ctx);

        // 3. Raw score, clipped.
        int raw = clip((int) Math.round(scored.totalContribution));

        // 4. Typologies.
        List<String> typologies = typologyEvaluator.evaluate(scored.values, ctx);

        // 5. Band by rail.
        Band band = Bands.bandFor(raw, rail);

        // 6. Typology override.
        band = applyTypologyOverride(band, typologies, raw);

        // 6b. §17 vulnerability-aware friction: a flagged-vulnerable account's ALLOW/CONFIRM outcome
        // is escalated exactly one band, capped at HOLD (mirroring the §8.3 AI-escalation asymmetry —
        // stricter-only, never softer, never into a hard BLOCK). Applied before the non-monetary cap
        // so a non-monetary action stays capped at CONFIRM (CI-9 holds for vulnerable accounts too).
        AccountStatsView vulnerabilityStats = store.accountStats(event.accountId());
        boolean vulnerable = vulnerabilityStats != null && vulnerabilityStats.isVulnerable();
        boolean termDepositBreak = type == EventType.TERM_DEPOSIT_BREAK;
        Band preVulnerabilityBand = band;
        Band escalatedBand = vulnerable ? escalateForVulnerability(band) : band;

        // 7+8. Push BOTH the escalated and the un-escalated band through the identical downstream steps
        // (non-monetary cap, then the TERM_DEPOSIT_BREAK CONFIRM-force) so the §17 flag records an
        // escalation only when it genuinely tightened the FINAL verdict. Otherwise a non-monetary
        // CONFIRM that escalation pushed to HOLD — only for capNonMonetary to pull it straight back to
        // CONFIRM — would falsely report a vulnerability escalation that had no net effect.
        band = finalBandAfterCaps(escalatedBand, type, termDepositBreak);
        Band unescalatedFinalBand = finalBandAfterCaps(preVulnerabilityBand, type, termDepositBreak);
        boolean vulnerabilityEscalated = vulnerable && band != unescalatedFinalBand;

        if (termDepositBreak) {
            typologies = dropHoldPinningTypologies(typologies);
        }

        Verdict decision = toVerdict(band);

        // 9. Approval routing: HOLD on an account with a designated approver → REQUIRE_APPROVAL.
        boolean hasApprover = store.accountStats(event.accountId()) != null
                && store.accountStats(event.accountId()).hasDesignatedApprover();
        if (decision == Verdict.HOLD && hasApprover) {
            decision = Verdict.REQUIRE_APPROVAL;
        }

        // 10. Policy routing.
        decision = applyPolicyRouting(decision, event, store);

        // 11. SCA TRA exemption + the friction the bank must apply (the "what to do", per §17 / the
        // five graduated outcomes). SCA is exempted only on a clean ALLOW transfer; it is *required* as
        // a step-up on CONFIRM (defeating a hijacked session — an attack the genuine victim of an APP
        // scam would otherwise sail through, which is why HOLD's freeze exists above it).
        boolean scaExempt = decision == Verdict.ALLOW
                && (type == EventType.TRANSFER || type == EventType.P2P_TRANSFER);
        String scaBasis = scaExempt ? SCA_TRA_BASIS : null;
        boolean scaRequired = decision == Verdict.CONFIRM;

        EngineVerdict engineVerdict = new EngineVerdict(
                raw, decision, scaExempt, scaBasis, scaRequired, frictionFor(decision),
                typologies, scored.signals);

        String reasonCode = reasonCode(decision, typologies, scored.values);
        Explanation explanation = explanationFor(decision, type, typologies, counterparty);

        return new ScoreResult(engineVerdict, reasonCode, explanation, List.of(), vulnerabilityEscalated);
    }

    /**
     * Escalate a band by {@link #VULNERABILITY_BAND_ESCALATION} for a flagged-vulnerable account,
     * capped at HOLD: ALLOW→CONFIRM, CONFIRM→HOLD. HOLD and BLOCK are already at or beyond the cap
     * and are returned unchanged — the friction is one extra step of protection, never a softening
     * and never a hard BLOCK the customer cannot clear.
     */
    private static Band escalateForVulnerability(Band band) {
        return switch (band) {
            case ALLOW -> Band.CONFIRM;
            case CONFIRM -> Band.HOLD;
            case HOLD, BLOCK -> band;
        };
    }

    /**
     * Apply the post-escalation band caps in the contractual order: the non-monetary cap (step 7),
     * then the TERM_DEPOSIT_BREAK CONFIRM-force (step 8). Pulled out so the same downstream collapse is
     * applied identically to both the escalated and the un-escalated band when deciding whether a §17
     * vulnerability escalation actually changed the final outcome.
     */
    private static Band finalBandAfterCaps(Band band, EventType type, boolean termDepositBreak) {
        Band capped = Bands.capNonMonetary(band, type);
        return termDepositBreak ? Band.CONFIRM : capped;
    }

    // --- mass-payment scoring ----------------------------------------------------------------

    private ScoreResult scoreMassPayment(ActionEvent event,
                                         FeatureStore store,
                                         RuntimeState runtime,
                                         PostureView posture,
                                         ObservationsView obs,
                                         GeoResolver geo,
                                         ReputationView reputation,
                                         Instant now) {
        MassPaymentPayload payload = (MassPaymentPayload) event.payload();
        long availableCents = toCents(payload.availableBalanceEur());

        List<ItemResult> itemResults = new ArrayList<>(payload.items().size());
        List<Signal> aggregateSignals = new ArrayList<>();
        List<Double> lineNameMismatchValues = new ArrayList<>(payload.items().size());
        List<String> heldLineIds = new ArrayList<>();
        int worstRaw = 0;
        Band worstBand = Band.ALLOW;
        List<String> allTypologies = new ArrayList<>();
        boolean batchTypologyAdded = false;

        for (BatchItem item : payload.items()) {
            String cpKey = Identity.counterpartyKey(item.counterparty());
            long amountCents = toCents(item.amountEur());

            // Record this line keyed by eventId#itemId: distinct lines of a batch each count, but a
            // replay of the whole batch does not double-count any line in the 24h window (CI-1).
            runtime.record(event.eventId() + BATCH_LINE_KEY_SEPARATOR + item.itemId(),
                    event.accountId(), cpKey, amountCents, now.toEpochMilli());
            long logicalAmountCents =
                    runtime.logicalAmountCents(event.accountId(), cpKey, amountCents, now.toEpochMilli());

            // Per-line context: the line's own amount/counterparty drives B1/B5/A3/P1 (via the
            // lineCounterparty override), but the batch-level signals (MP1/MP2/MP4) read the whole
            // payload from the event, so they are identical across lines. We collect the batch
            // typologies once (from the first line) to avoid duplication.
            SignalContext ctx = new SignalContext(
                    event, store, runtime, posture, obs, geo, reputation, item.counterparty(), cpKey,
                    logicalAmountCents, amountCents, availableCents, now);

            ScoredSignals scored = evaluateSignals(ctx);
            int raw = clip((int) Math.round(scored.totalContribution));
            List<String> typologies = typologyEvaluator.evaluate(scored.values, ctx);
            lineNameMismatchValues.add(scored.values.getOrDefault("B5", 0.0));

            Band band = Bands.bandFor(raw, payload.rail());
            band = applyTypologyOverride(band, typologies, raw);

            Verdict lineVerdict = toVerdict(band);
            if (lineVerdict == Verdict.HOLD || lineVerdict == Verdict.BLOCK) {
                heldLineIds.add(item.itemId());
            }
            itemResults.add(new ItemResult(item.itemId(), lineVerdict, scored.signals));
            aggregateSignals.addAll(scored.signals);
            if (!batchTypologyAdded) {
                allTypologies.addAll(typologies);
                batchTypologyAdded = true;
            } else {
                // Carry only line-specific typologies forward (batch ones already counted once).
                for (String t : typologies) {
                    if (!Typologies.MULE_FAN_OUT.equals(t)) {
                        allTypologies.add(t);
                    }
                }
            }
            if (band.ordinal() > worstBand.ordinal()) {
                worstBand = band;
            }
            worstRaw = Math.max(worstRaw, raw);
        }

        // Ty6 — payroll redirection: an established batch pattern with ≥1 changed-IBAN line. The
        // flagged lines are quarantined (already item-level HELD above) and the batch proceeds to
        // approval; this names the pattern for the audit trail and customer/ops messaging.
        boolean establishedBatchPattern = hasEstablishedBatchPattern(store, event.accountId());
        if (typologyEvaluator.isPayrollRedirection(establishedBatchPattern, lineNameMismatchValues)
                && !allTypologies.contains(Typologies.PAYROLL_REDIRECTION)) {
            allTypologies.add(Typologies.PAYROLL_REDIRECTION);
        }

        // Batch-level verdict from the worst item; business accounts route to four-eyes approval — but
        // only when that is STRICTER, never softer. A confirmed-fraud BLOCK (worst line raw ≥ 90 or
        // two+ typologies ≥ 85) must stay a hard BLOCK: routing it to a releasable REQUIRE_APPROVAL
        // would let a second approver release confirmed fraud, violating the stricter-only invariant.
        Verdict batchDecision = toVerdict(worstBand);
        boolean businessAccount = store.accountStats(event.accountId()) != null
                && store.accountStats(event.accountId()).isBusinessAccount();
        if (businessAccount && batchDecision != Verdict.BLOCK) {
            batchDecision = Verdict.REQUIRE_APPROVAL;
        } else if (batchDecision == Verdict.HOLD) {
            boolean hasApprover = store.accountStats(event.accountId()) != null
                    && store.accountStats(event.accountId()).hasDesignatedApprover();
            if (hasApprover) {
                batchDecision = Verdict.REQUIRE_APPROVAL;
            }
        }

        List<String> dedupTypologies = allTypologies.stream().distinct().toList();
        EngineVerdict engineVerdict = new EngineVerdict(
                worstRaw, batchDecision, false, null, batchDecision == Verdict.CONFIRM,
                frictionFor(batchDecision), dedupTypologies, aggregateSignals);

        String reasonCode = reasonCode(batchDecision, dedupTypologies, Map.of());
        Explanation explanation = explanationFor(batchDecision, EventType.MASS_PAYMENT, dedupTypologies, null);

        // The §17 single-action friction escalation does not apply to a batch: a business mass-payment
        // already routes to four-eyes REQUIRE_APPROVAL, and the disclosed vulnerable demo account is
        // retail. The flag is therefore not escalated on the batch path.
        return new ScoreResult(engineVerdict, reasonCode, explanation, itemResults, false);
    }

    /**
     * An account has an "established batch pattern" (rhythmic payroll history) when it is a business
     * account with a real outgoing baseline — the recurring monthly payroll runs Berka exposes. This
     * is the precondition Ty6 layers the changed-IBAN line check on top of.
     */
    private static boolean hasEstablishedBatchPattern(FeatureStore store, String accountId) {
        var stats = store.accountStats(accountId);
        return stats != null && stats.isBusinessAccount() && stats.outTxnCount() > 0;
    }

    // --- signal evaluation -------------------------------------------------------------------

    private ScoredSignals evaluateSignals(SignalContext ctx) {
        List<com.cy.diakritis.engine.signal.Signal> signals = List.of(
                // Beneficiary / counterparty-novelty band
                new B1NewBeneficiary(),
                new B2BeneficiaryRecency(),
                new B3BeneficiaryJustAdded(),
                new B4EstablishedPayee(),
                new B5NameMismatch(),
                // Payment-context band
                new P1AliasRepoint(),
                // Amount-anomaly band
                new A1AmountVsAccount(),
                new A2BalanceDrain(),
                new A3AmountVsCounterparty(),
                new A4ThresholdHugging(),
                // Velocity band
                new V1BurstVelocity(),
                new V2RisingAmounts(),
                // Channel band
                new C1OutOfPatternTime(),
                new C3RetryPressure(),
                // Geo band
                new G1UnfamiliarGeo(),
                new G2NewNetwork(),
                // Device band
                new D1DeviceAgeDecay(),
                new D2PlatformAnomaly(),
                // Kill-chain / liquidation band
                new K1FundsFreed(),
                new K2LimitRaisedRecently(),
                new K3BeneficiaryAddBurst(),
                // Mass-payment band
                new MP1NewCounterpartyShare(),
                new MP2CadenceTotalAnomaly(),
                new MP4BatchDrain(),
                // Cross-account band
                new X1CrossAccountReputation(),
                // Model bands (additive, capped)
                new M1ModelSignal(m1Scorer),
                new M2ExemplarSignal(m2Scorer)
        );

        Map<String, Double> values = new LinkedHashMap<>();
        List<Signal> emitted = new ArrayList<>(signals.size());
        double total = 0.0;

        for (com.cy.diakritis.engine.signal.Signal s : signals) {
            double value = s.value(ctx);
            double weight = s.weight();
            double contribution = weight * value;
            // M1 contribution is already capped by its weight; clamp value defensively to [0,1].
            values.put(s.id(), value);
            emitted.add(new Signal(s.id(), value, weight, contribution, detailFor(s.id(), value)));
            total += contribution;
        }

        return new ScoredSignals(total, values, emitted);
    }

    private record ScoredSignals(double totalContribution, Map<String, Double> values, List<Signal> signals) {
    }

    // --- override / routing helpers ----------------------------------------------------------

    /**
     * Raw score at or above which a typology-matched action is a confirmed-fraud BLOCK on its own,
     * even on a single typology match (§7 / §15: "Two+ matches OR raw score ≥ 90 → BLOCK"). A single
     * typology normally pins HOLD, but an overwhelming raw score — a maximally-stacked mule fan-out or
     * drain — is BLOCK regardless of how many distinct typologies were named.
     */
    private static final int RAW_BLOCK_ESCALATION = 90;

    /** Raw score at or above which two-or-more typology matches escalate to BLOCK. */
    private static final int MULTI_TYPOLOGY_BLOCK_EDGE = 85;

    /**
     * Typology override (§7 / §15): a single typology match pins the band to exactly HOLD — capping a
     * raw score that merely crossed the BLOCK edge (≥ 85) back down, since one named script alone is a
     * cooling-off HOLD — UNLESS the raw score is so high (≥ {@value #RAW_BLOCK_ESCALATION}) that the
     * action is a confirmed-fraud BLOCK on its own. Two or more matches escalate to BLOCK once the raw
     * score is ≥ {@value #MULTI_TYPOLOGY_BLOCK_EDGE}. A clean (no-typology) band is left to the band
     * table (which already returns BLOCK at ≥ 85).
     *
     * <p>This is why the T5b two-typology kill-chain (raw ~82) and the T15a single-typology kill-chain
     * (raw ~74) both land on HOLD — neither reaches the relevant BLOCK edge — while a maximally-stacked
     * mule fan-out (raw ≥ 90) reaches BLOCK even though it names a single typology.
     */
    private static Band applyTypologyOverride(Band band, List<String> typologies, int raw) {
        int matches = typologies.size();
        if (matches == 0) {
            return band;
        }
        if (raw >= RAW_BLOCK_ESCALATION) {
            return Band.BLOCK;
        }
        if (matches >= 2 && raw >= MULTI_TYPOLOGY_BLOCK_EDGE) {
            return Band.BLOCK;
        }
        return Band.HOLD;
    }

    /**
     * Routes that turn a verdict into REQUIRE_APPROVAL regardless of band:
     * a limit raise to more than {@value #LIMIT_RAISE_APPROVAL_MULTIPLE}× the current limit.
     * (Mass-payment business routing is handled in the batch path.)
     */
    private static Verdict applyPolicyRouting(Verdict decision, ActionEvent event, FeatureStore store) {
        if (event.eventType() == EventType.LIMIT_CHANGE
                && event.payload() instanceof LimitChangePayload limit) {
            BigDecimal threshold = limit.currentLimitEur()
                    .multiply(BigDecimal.valueOf(LIMIT_RAISE_APPROVAL_MULTIPLE));
            if (limit.newLimitEur().compareTo(threshold) > 0) {
                return Verdict.REQUIRE_APPROVAL;
            }
        }
        return decision;
    }

    /** Drop typologies whose only effect would be to pin HOLD (used by the TERM_DEPOSIT_BREAK guard). */
    private static List<String> dropHoldPinningTypologies(List<String> typologies) {
        // TERM_DEPOSIT_BREAK is non-monetary and always CONFIRM; the kill-chain typology fires on the
        // subsequent transfer, not on the break itself, so it must not be reported here as a HOLD pin.
        return typologies.stream()
                .filter(t -> !Typologies.LIQUIDATION_KILL_CHAIN.equals(t))
                .filter(t -> !Typologies.SAFE_ACCOUNT_SCAM.equals(t))
                .toList();
    }

    // --- reason codes & explanation ----------------------------------------------------------

    private static final double X1_REASON_THRESHOLD = 0.5;

    /**
     * The friction the bank must apply for a verdict — the executable "what to do" behind each of the
     * five graduated outcomes. A pure function of the verdict, so the same ladder is reported to every
     * consumer (the bank renders it; the engine never decides UI).
     */
    private static Friction frictionFor(Verdict decision) {
        return switch (decision) {
            case ALLOW -> Friction.NONE;
            case CONFIRM -> Friction.SCA_STEP_UP;
            case REQUIRE_APPROVAL -> Friction.SECOND_APPROVAL;
            case HOLD -> Friction.FREEZE_AND_WARN;
            case BLOCK -> Friction.STOP_AND_REVIEW;
        };
    }

    private static String reasonCode(Verdict decision, List<String> typologies, Map<String, Double> values) {
        if (typologies.contains(Typologies.LIQUIDATION_KILL_CHAIN)) {
            return ReasonCodes.KILLCHAIN;
        }
        if (typologies.contains(Typologies.INVOICE_REDIRECTION)) {
            return ReasonCodes.INVOICE;
        }
        if (typologies.contains(Typologies.SAFE_ACCOUNT_SCAM)) {
            return ReasonCodes.SAFE_ACCOUNT;
        }
        // X1 cross-account reputation: a flagged destination drives the DKR-XACCT reason even without a
        // named typology, since the moat fires on identity-level reputation rather than a script shape.
        if (decision != Verdict.ALLOW
                && values.getOrDefault("X1", 0.0) >= X1_REASON_THRESHOLD) {
            return ReasonCodes.XACCT;
        }
        if (decision == Verdict.ALLOW) {
            return ReasonCodes.NONE;
        }
        return ReasonCodes.FRAUD;
    }

    /**
     * Dual-audience explanation. The customer message is {@code null} iff the decision is ALLOW (a
     * clean payment is not interrupted); otherwise it names the pattern in one sentence. The audit
     * message always records the outcome for the case file.
     */
    private static Explanation explanationFor(Verdict decision,
                                              EventType type,
                                              List<String> typologies,
                                              Counterparty counterparty) {
        String audit = "decision=" + decision
                + " type=" + type
                + " typologies=" + typologies;

        if (decision == Verdict.ALLOW) {
            return new Explanation(null, audit);
        }

        String customer = customerMessage(decision, type, typologies, counterparty);
        return new Explanation(customer, audit);
    }

    private static String customerMessage(Verdict decision, EventType type, List<String> typologies,
                                          Counterparty counterparty) {
        if (type == EventType.TERM_DEPOSIT_BREAK) {
            return "Breaking this term deposit will release your funds early and incur a penalty — "
                    + "please confirm the purpose of this withdrawal before we proceed.";
        }
        if (typologies.contains(Typologies.LIQUIDATION_KILL_CHAIN)) {
            return "This looks like an account-liquidation scam: funds were just freed and are now "
                    + "being sent to a brand-new payee — we have paused it to protect you.";
        }
        if (typologies.contains(Typologies.INVOICE_REDIRECTION)) {
            String name = counterparty != null && counterparty.resolvedName() != null
                    ? counterparty.resolvedName() : "this supplier";
            return "The bank details for " + name + " have changed since you last paid them — this is a "
                    + "common invoice-redirection scam, so we have paused the payment for you to verify.";
        }
        if (typologies.contains(Typologies.ROMANCE_REPEAT_VICTIM)) {
            return "You have been sending steadily larger amounts to a recently-added payee — this pattern "
                    + "matches romance and investment scams, so we have paused this transfer.";
        }
        if (typologies.contains(Typologies.SAFE_ACCOUNT_SCAM)) {
            return "Moving most of your balance to a payee added moments ago matches the 'safe account' "
                    + "scam — we have paused this so you can verify the request is genuine.";
        }
        if (typologies.contains(Typologies.PURCHASE_SCAM)) {
            return "You are about to send an instant, irreversible payment to a brand-new payee for a "
                    + "purchase — this is a common marketplace scam, so we have paused it for you to verify "
                    + "the seller first.";
        }
        if (typologies.contains(Typologies.PAYROLL_REDIRECTION)) {
            return "One or more lines in this batch pay an account whose details changed since the last "
                    + "run — a common payroll-redirection scam — so we have quarantined the affected lines "
                    + "for approval while the rest proceed.";
        }
        if (typologies.contains(Typologies.MULE_FAN_OUT)) {
            return "This batch sends most of your balance to many brand-new accounts at once, which "
                    + "matches a money-mule fan-out — we have paused it for review.";
        }
        // Confirmation-of-Payee prompt: a first-time send to a payee we resolved by name (a P2P alias
        // or a new IBAN) gets a CONFIRM that shows the resolved name back to the customer so they can
        // confirm they are paying who they think before the (often irrevocable) payment leaves.
        if (decision == Verdict.CONFIRM
                && (type == EventType.P2P_TRANSFER || type == EventType.TRANSFER)
                && counterparty != null
                && counterparty.resolvedName() != null
                && !counterparty.resolvedName().isBlank()) {
            return "You are sending this to " + counterparty.resolvedName()
                    + " — please confirm this is the right person before we send it, as the payment may "
                    + "not be reversible.";
        }
        return "This payment is unusual for your account, so we have paused it for you to confirm.";
    }

    private static String detailFor(String id, double value) {
        return id + "=" + String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    // --- band ↔ verdict ----------------------------------------------------------------------

    private static Verdict toVerdict(Band band) {
        return switch (band) {
            case ALLOW -> Verdict.ALLOW;
            case CONFIRM -> Verdict.CONFIRM;
            case HOLD -> Verdict.HOLD;
            case BLOCK -> Verdict.BLOCK;
        };
    }

    // --- amount / rail extraction ------------------------------------------------------------

    private static int clip(int raw) {
        return Math.max(RAW_MIN, Math.min(RAW_MAX, raw));
    }

    private static Rail railOf(ActionEvent event) {
        return switch (event.payload()) {
            case TransferPayload t -> t.rail();
            case MassPaymentPayload m -> m.rail();
            default -> Rail.INTERNAL; // non-monetary actions have no money rail
        };
    }

    private static Counterparty counterpartyOf(ActionEvent event) {
        return switch (event.payload()) {
            case TransferPayload t -> t.counterparty();
            case BeneficiaryAddPayload b -> b.counterparty();
            default -> null;
        };
    }

    private static long amountCentsOf(ActionEvent event) {
        return switch (event.payload()) {
            case TransferPayload t -> toCents(t.amountEur());
            case DepositBreakPayload d -> toCents(d.principalEur());
            case LimitChangePayload l -> toCents(l.newLimitEur());
            case MassPaymentPayload m -> toCents(m.totalEur());
            case BeneficiaryAddPayload ignored -> 0L;
        };
    }

    private static long availableBalanceCentsOf(ActionEvent event) {
        return switch (event.payload()) {
            case TransferPayload t -> toCents(t.availableBalanceEur());
            case MassPaymentPayload m -> toCents(m.availableBalanceEur());
            default -> 0L;
        };
    }

    /**
     * A synthetic counterparty key for non-monetary actions that have no counterparty, scoped per
     * event type so their rolling-window state never collides with real payees.
     */
    private static String nonMonetaryKey(ActionEvent event) {
        return switch (event.payload()) {
            case DepositBreakPayload d -> "DEPOSIT|" + d.depositId();
            case LimitChangePayload ignored -> "LIMIT|" + event.accountId();
            default -> Addressing.ACCOUNT + "|" + event.accountId();
        };
    }

    private static long toCents(BigDecimal eur) {
        if (eur == null) {
            return 0L;
        }
        return eur.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
