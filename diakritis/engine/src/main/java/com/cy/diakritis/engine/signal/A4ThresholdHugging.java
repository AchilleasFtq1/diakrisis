package com.cy.diakritis.engine.signal;

import com.cy.diakritis.engine.band.Weights;

/**
 * A4 — threshold hugging: the amount sits just below a round reporting/limit boundary. Structuring
 * payments to land at €999, €4,950 or €9,990 to slip under €1,000 / €5,000 / €10,000 controls is a
 * classic laundering / coached-scam tell. Fires when the amount is within
 * {@link Weights#A4_HUG_FRACTION} below one of the round thresholds; the signal grows toward 1.0 the
 * closer it hugs the boundary, and is 0 above the boundary or comfortably below it.
 */
public final class A4ThresholdHugging implements Signal {

    /** Round euro boundaries that fraud commonly hugs (in euro-cents). */
    private static final long[] THRESHOLDS_CENTS = {
            100_000L,     // €1,000
            500_000L,     // €5,000
            1_000_000L,   // €10,000
            1_500_000L,   // €15,000
            5_000_000L    // €50,000
    };

    @Override
    public String id() {
        return "A4";
    }

    @Override
    public double weight() {
        return Weights.A4;
    }

    @Override
    public double value(SignalContext ctx) {
        long amount = ctx.logicalAmountCents();
        if (amount <= 0) {
            return 0.0;
        }
        double best = 0.0;
        for (long threshold : THRESHOLDS_CENTS) {
            double band = threshold * Weights.A4_HUG_FRACTION;
            if (band <= 0) {
                continue;
            }
            double gap = threshold - amount;
            // Just below the threshold (0 < gap <= band) → hugging; closer to the line scores higher.
            if (gap > 0 && gap <= band) {
                double strength = 1.0 - (gap / band);
                best = Math.max(best, strength);
            }
        }
        return SignalMath.clamp01(best);
    }
}
