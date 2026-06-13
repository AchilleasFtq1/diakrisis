package com.cy.diakritis.engine.pipeline;

import com.cy.diakritis.common.dto.ActionEvent;
import com.cy.diakritis.common.dto.Addressing;
import com.cy.diakritis.common.dto.BatchItem;
import com.cy.diakritis.common.dto.BeneficiaryAddPayload;
import com.cy.diakritis.common.dto.Counterparty;
import com.cy.diakritis.common.dto.DepositBreakPayload;
import com.cy.diakritis.common.dto.EngineVerdict;
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
import com.cy.diakritis.engine.signal.A1AmountVsAccount;
import com.cy.diakritis.engine.signal.A2BalanceDrain;
import com.cy.diakritis.engine.signal.A3AmountVsCounterparty;
import com.cy.diakritis.engine.signal.B1NewBeneficiary;
import com.cy.diakritis.engine.signal.B2BeneficiaryRecency;
import com.cy.diakritis.engine.signal.B3BeneficiaryJustAdded;
import com.cy.diakritis.engine.signal.B4EstablishedPayee;
import com.cy.diakritis.engine.signal.B5NameMismatch;
import com.cy.diakritis.engine.signal.Identity;
import com.cy.diakritis.engine.signal.K1FundsFreed;
import com.cy.diakritis.engine.signal.M1ModelSignal;
import com.cy.diakritis.engine.signal.SignalContext;
import com.cy.diakritis.engine.signal.V2RisingAmounts;
import com.cy.diakritis.engine.store.FeatureStore;
import com.cy.diakritis.engine.store.ObservationsView;
import com.cy.diakritis.engine.store.PostureView;
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

    public static final String SCA_TRA_BASIS =
            "PSD2 RTS Art.18 TRA (low value, low fraud-rate)";

    private final M1Scorer m1Scorer;
    private final TypologyEvaluator typologyEvaluator;

    public ScoreEngine(M1Scorer m1Scorer, TypologyEvaluator typologyEvaluator) {
        this.m1Scorer = m1Scorer;
        this.typologyEvaluator = typologyEvaluator;
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
        return switch (event.eventType()) {
            case MASS_PAYMENT -> scoreMassPayment(event, store, runtime, posture, obs, now);
            default -> scoreSingle(event, store, runtime, posture, obs, now);
        };
    }

    // --- single-action scoring ---------------------------------------------------------------

    private ScoreResult scoreSingle(ActionEvent event,
                                    FeatureStore store,
                                    RuntimeState runtime,
                                    PostureView posture,
                                    ObservationsView obs,
                                    Instant now) {
        EventType type = event.eventType();
        Rail rail = railOf(event);
        Counterparty counterparty = counterpartyOf(event);
        String cpKey = counterparty != null ? Identity.counterpartyKey(counterparty) : nonMonetaryKey(event);

        long amountCents = amountCentsOf(event);
        long availableCents = availableBalanceCentsOf(event);

        // 1. Record the current event so the rolling-window logical amount includes it.
        runtime.record(event.accountId(), cpKey, amountCents, now.toEpochMilli());
        if (type == EventType.BENEFICIARY_ADD && event.context() != null) {
            runtime.recordBeneficiaryAdd(event.context().sessionId(), now.toEpochMilli());
        }

        long logicalAmountCents = runtime.logicalAmountCents(event.accountId(), cpKey, amountCents, now.toEpochMilli());

        SignalContext ctx = new SignalContext(
                event, store, runtime, posture, obs, cpKey,
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

        // 7. Non-monetary cap.
        band = Bands.capNonMonetary(band, type);

        // 8. TERM_DEPOSIT_BREAK guard: force CONFIRM, drop HOLD-pinning typologies.
        boolean termDepositBreak = type == EventType.TERM_DEPOSIT_BREAK;
        if (termDepositBreak) {
            band = Band.CONFIRM;
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

        // 11. SCA TRA exemption.
        boolean scaExempt = decision == Verdict.ALLOW
                && (type == EventType.TRANSFER || type == EventType.P2P_TRANSFER);
        String scaBasis = scaExempt ? SCA_TRA_BASIS : null;

        EngineVerdict engineVerdict = new EngineVerdict(
                raw, decision, scaExempt, scaBasis, typologies, scored.signals);

        String reasonCode = reasonCode(decision, typologies, scored.values);
        Explanation explanation = explanationFor(decision, type, typologies, counterparty);

        return new ScoreResult(engineVerdict, reasonCode, explanation, List.of());
    }

    // --- mass-payment scoring ----------------------------------------------------------------

    private ScoreResult scoreMassPayment(ActionEvent event,
                                         FeatureStore store,
                                         RuntimeState runtime,
                                         PostureView posture,
                                         ObservationsView obs,
                                         Instant now) {
        MassPaymentPayload payload = (MassPaymentPayload) event.payload();
        long availableCents = toCents(payload.availableBalanceEur());

        List<ItemResult> itemResults = new ArrayList<>(payload.items().size());
        List<Signal> aggregateSignals = new ArrayList<>();
        int worstRaw = 0;
        Band worstBand = Band.ALLOW;
        List<String> allTypologies = new ArrayList<>();

        for (BatchItem item : payload.items()) {
            String cpKey = Identity.counterpartyKey(item.counterparty());
            long amountCents = toCents(item.amountEur());

            runtime.record(event.accountId(), cpKey, amountCents, now.toEpochMilli());
            long logicalAmountCents =
                    runtime.logicalAmountCents(event.accountId(), cpKey, amountCents, now.toEpochMilli());

            SignalContext ctx = new SignalContext(
                    event, store, runtime, posture, obs, cpKey,
                    logicalAmountCents, amountCents, availableCents, now);

            ScoredSignals scored = evaluateSignals(ctx);
            int raw = clip((int) Math.round(scored.totalContribution));
            List<String> typologies = typologyEvaluator.evaluate(scored.values, ctx);

            Band band = Bands.bandFor(raw, payload.rail());
            band = applyTypologyOverride(band, typologies, raw);

            itemResults.add(new ItemResult(item.itemId(), toVerdict(band), scored.signals));
            aggregateSignals.addAll(scored.signals);
            allTypologies.addAll(typologies);
            if (band.ordinal() > worstBand.ordinal()) {
                worstBand = band;
            }
            worstRaw = Math.max(worstRaw, raw);
        }

        // Batch-level verdict from the worst item; business accounts route to approval.
        Verdict batchDecision = toVerdict(worstBand);
        boolean businessAccount = store.accountStats(event.accountId()) != null
                && store.accountStats(event.accountId()).isBusinessAccount();
        if (businessAccount) {
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
                worstRaw, batchDecision, false, null, dedupTypologies, aggregateSignals);

        String reasonCode = reasonCode(batchDecision, dedupTypologies, Map.of());
        Explanation explanation = explanationFor(batchDecision, EventType.MASS_PAYMENT, dedupTypologies, null);

        return new ScoreResult(engineVerdict, reasonCode, explanation, itemResults);
    }

    // --- signal evaluation -------------------------------------------------------------------

    private ScoredSignals evaluateSignals(SignalContext ctx) {
        List<com.cy.diakritis.engine.signal.Signal> signals = List.of(
                new B1NewBeneficiary(),
                new B2BeneficiaryRecency(),
                new B3BeneficiaryJustAdded(),
                new B4EstablishedPayee(),
                new B5NameMismatch(),
                new A1AmountVsAccount(),
                new A2BalanceDrain(),
                new A3AmountVsCounterparty(),
                new V2RisingAmounts(),
                new K1FundsFreed(),
                new M1ModelSignal(m1Scorer)
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
     * Typology override (contract): a single typology match pins the band to exactly HOLD — including
     * capping a raw score that crossed the BLOCK edge (≥ 85) back down, since BLOCK is reserved for
     * confirmed multi-typology liquidation. Two or more matches escalate to BLOCK only when the raw
     * score is also ≥ 85; below that they remain HOLD. A clean (no-typology) band is unchanged.
     *
     * <p>This is why the T5b single-typology kill-chain (raw ~85) lands on HOLD rather than BLOCK:
     * one typology never reaches BLOCK regardless of where the raw score rounds.
     */
    private static Band applyTypologyOverride(Band band, List<String> typologies, int raw) {
        int matches = typologies.size();
        if (matches == 0) {
            return band;
        }
        if (matches >= 2 && raw >= 85) {
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

        String customer = customerMessage(type, typologies, counterparty);
        return new Explanation(customer, audit);
    }

    private static String customerMessage(EventType type, List<String> typologies, Counterparty counterparty) {
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
