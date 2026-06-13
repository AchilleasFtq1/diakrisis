package com.cy.diakritis.engine.signal;

import com.cy.diakritis.engine.band.Weights;

/**
 * K1 — liquidation kill-chain: funds were recently freed (e.g. a term deposit broken) and are now
 * being moved out. When the 72h freed-funds posture covers this payment, the signal is
 * {@code min(1, freed/amount)} scaled by a 72h exponential decay from the last freeing event, so a
 * fresh "break the deposit, then sweep it" sequence scores near 1 and the link weakens over the
 * window. Zero when nothing was freed or the freed amount does not cover the payment.
 */
public final class K1FundsFreed implements Signal {

    private static final double WINDOW_HOURS = 72.0;
    private static final double WINDOW_MS = WINDOW_HOURS * 60.0 * 60.0 * 1000.0;
    /** Decay tau chosen so the freed-funds link has fully relaxed by the 72h window edge. */
    private static final double DECAY_TAU_MS = WINDOW_MS / 3.0;

    @Override
    public String id() {
        return "K1";
    }

    @Override
    public double weight() {
        return Weights.K1;
    }

    @Override
    public double value(SignalContext ctx) {
        long freed = ctx.posture().fundsFreedEur72hCents();
        long amount = ctx.logicalAmountCents();
        if (freed <= 0 || amount <= 0 || freed < amount) {
            return 0.0;
        }

        double coverage = Math.min(1.0, (double) freed / (double) amount);

        long lastBreak = ctx.posture().lastDepositBreakEpochMs();
        double decay = 1.0;
        if (lastBreak > 0) {
            double ageMs = ctx.now().toEpochMilli() - lastBreak;
            if (ageMs < 0) {
                ageMs = 0;
            }
            if (ageMs > WINDOW_MS) {
                return 0.0;
            }
            decay = Math.exp(-ageMs / DECAY_TAU_MS);
        }

        return SignalMath.clamp01(coverage * decay);
    }
}
