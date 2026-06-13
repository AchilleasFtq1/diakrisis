package com.cy.diakritis.engine.typology;

import com.cy.diakritis.common.dto.MassPaymentPayload;
import com.cy.diakritis.common.dto.Rail;
import com.cy.diakritis.common.dto.TransferPayload;
import com.cy.diakritis.engine.band.Weights;
import com.cy.diakritis.engine.signal.Identity;
import com.cy.diakritis.engine.signal.SignalContext;
import com.cy.diakritis.engine.store.CounterpartyByNameView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Recognises named scam typologies from the evaluated signal values plus context. Each rule is a
 * conjunction of signal conditions; a matched typology is a stronger, more explainable statement
 * than any single signal and drives band overrides and customer messaging downstream.
 *
 * <p>The evaluator is pure: given the same signal map and context it returns the same list, in a
 * deterministic order (kill-chain, invoice, safe-account, romance) so reason-code selection is
 * stable.
 */
public final class TypologyEvaluator {

    private static final double EPS = 1e-9;

    /**
     * Evaluate every typology rule. {@code values} maps signal id → evaluated value in {@code [0,1]}
     * (or the signed contribution-independent strength); {@code ctx} supplies the "established
     * counterparty" facts the invoice rule needs.
     */
    public List<String> evaluate(Map<String, Double> values, SignalContext ctx) {
        List<String> matched = new ArrayList<>();

        double b1 = value(values, "B1");
        double b2 = value(values, "B2");
        double b3 = value(values, "B3");
        double b5 = value(values, "B5");
        double a2 = value(values, "A2");
        double a3 = value(values, "A3");
        double d1 = value(values, "D1");
        double g1 = value(values, "G1");
        double v2 = value(values, "V2");
        double k1 = value(values, "K1");
        double mp1 = value(values, "MP1");
        double mp4 = value(values, "MP4");

        // Ty5 — liquidation kill-chain: freed funds being swept out of a brand-new payee.
        if (k1 > 0.6 && isOne(b1) && a2 > 0.6) {
            matched.add(Typologies.LIQUIDATION_KILL_CHAIN);
        }

        // Ty2 — invoice redirection: a name we know, but the IBAN behind it changed, on an
        // established supplier, with an anomalous amount.
        if (isOne(b5) && a3 > 0 && isEstablishedCounterparty(ctx)) {
            matched.add(Typologies.INVOICE_REDIRECTION);
        }

        // Ty1 — safe-account scam: new payee, sweeping the balance, with an in-session add, a
        // brand-new device, or a sudden new geography (any of the three corroborating tells, §7).
        if (isOne(b1) && a2 > 0.7 && (b3 > 0 || d1 > 0.5 || g1 > 0.5)) {
            matched.add(Typologies.SAFE_ACCOUNT_SCAM);
        }

        // Ty3 — purchase scam: a brand-new payee, on an irrevocable real-time rail, for a first-time
        // modest amount (the marketplace "pay by instant/P2P now" trap). The modest cap distinguishes
        // it from the safe-account sweep (which drains the balance).
        if (isOne(b1) && isRealtimeRail(ctx) && isFirstTimeModestAmount(ctx)) {
            matched.add(Typologies.PURCHASE_SCAM);
        }

        // Ty4 — romance / repeat-victim grooming: rising payments to a still-fresh relationship.
        if (v2 > 0 && b2 > 0.4) {
            matched.add(Typologies.ROMANCE_REPEAT_VICTIM);
        }

        // Ty7 — mule fan-out: a batch with a high new-counterparty share that also drains the
        // balance — a compromised account spraying fresh mules in one file. Batch HOLD/BLOCK.
        if (mp1 > Weights.TY7_MP1_THRESHOLD && mp4 > Weights.TY7_MP4_THRESHOLD) {
            matched.add(Typologies.MULE_FAN_OUT);
        }

        return matched;
    }

    /**
     * Ty6 — payroll redirection: an established corporate batch pattern with at least one line whose
     * salary IBAN changed (per-line B5 / changed-ref). Unlike the others this is a quarantine rule —
     * the flagged lines are held while the clean lines proceed to approval — so it is evaluated over
     * the per-line results, not the batch-aggregate signal map.
     *
     * @param batchHasEstablishedPattern whether the account has a recurring batch baseline (rhythmic
     *                                   payroll history) — supplied by the pipeline from posture/stats
     * @param lineNameMismatchValues     per-line B5 (name/IBAN mismatch) values for the batch
     * @return true when the payroll-redirection pattern is present (established pattern ∧ ≥1 mismatch)
     */
    public boolean isPayrollRedirection(boolean batchHasEstablishedPattern, List<Double> lineNameMismatchValues) {
        if (!batchHasEstablishedPattern || lineNameMismatchValues == null) {
            return false;
        }
        return lineNameMismatchValues.stream().anyMatch(v -> v != null && isOne(v));
    }

    private static boolean isRealtimeRail(SignalContext ctx) {
        Rail rail = switch (ctx.event().payload()) {
            case TransferPayload t -> t.rail();
            default -> null;
        };
        return rail == Rail.P2P || rail == Rail.INSTANT;
    }

    private static boolean isFirstTimeModestAmount(SignalContext ctx) {
        return ctx.amountCents() > 0 && ctx.amountCents() <= Weights.TY3_MODEST_AMOUNT_CENTS;
    }

    /**
     * "Established" for the invoice rule: the name-indexed counterparty has at least
     * {@link Weights#TY2_ESTABLISHED_MIN_PAYMENTS} prior payments and is at least
     * {@link Weights#TY2_ESTABLISHED_MIN_AGE_DAYS} days old.
     */
    private static boolean isEstablishedCounterparty(SignalContext ctx) {
        String resolvedName = resolvedName(ctx);
        if (resolvedName == null || resolvedName.isBlank()) {
            return false;
        }
        Optional<CounterpartyByNameView> view =
                ctx.store().byName(ctx.accountId(), Identity.normalizeName(resolvedName));
        if (view.isEmpty()) {
            return false;
        }
        CounterpartyByNameView v = view.get();
        if (v.payCount() < Weights.TY2_ESTABLISHED_MIN_PAYMENTS) {
            return false;
        }
        double ageDays = (ctx.now().toEpochMilli() - v.firstSeenEpochMs()) / (24.0 * 60.0 * 60.0 * 1000.0);
        return ageDays >= Weights.TY2_ESTABLISHED_MIN_AGE_DAYS;
    }

    private static String resolvedName(SignalContext ctx) {
        return switch (ctx.event().payload()) {
            case com.cy.diakritis.common.dto.TransferPayload t ->
                    t.counterparty() == null ? null : t.counterparty().resolvedName();
            case com.cy.diakritis.common.dto.BeneficiaryAddPayload b ->
                    b.counterparty() == null ? null : b.counterparty().resolvedName();
            default -> null;
        };
    }

    private static double value(Map<String, Double> values, String id) {
        Double v = values.get(id);
        return v == null ? 0.0 : v;
    }

    private static boolean isOne(double v) {
        return Math.abs(v - 1.0) < EPS;
    }
}
