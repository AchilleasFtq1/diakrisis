package com.cy.diakritis.engine.signal;

import com.cy.diakritis.engine.band.Weights;

/**
 * K2 — limit raised recently: the account's transfer limit was increased within the lookback window
 * ({@link Weights#POSTURE_LIMIT_RAISED_WINDOW_HOURS}) and this payment exploits the new headroom.
 * Raising the limit and immediately moving more money is a coached-scam staple — the scammer walks
 * the victim through "increase your limit, now send". The signal scales with how much of this
 * payment the recently-raised headroom covers, saturating at
 * {@link Weights#K2_COVERAGE_SATURATION}× the amount.
 *
 * <p>Zero when no recent raise is on posture or the amount is non-positive.
 */
public final class K2LimitRaisedRecently implements Signal {

    @Override
    public String id() {
        return "K2";
    }

    @Override
    public double weight() {
        return Weights.K2;
    }

    @Override
    public double value(SignalContext ctx) {
        long raised = ctx.posture().limitRaised72hCents();
        long amount = ctx.logicalAmountCents();
        if (raised <= 0 || amount <= 0) {
            return 0.0;
        }
        double saturation = Weights.K2_COVERAGE_SATURATION;
        if (saturation <= 0) {
            return 0.0;
        }
        // How much of this payment the fresh headroom covers, normalized to the saturation multiple.
        double coverage = ((double) raised / (double) amount) / saturation;
        return SignalMath.clamp01(coverage);
    }
}
