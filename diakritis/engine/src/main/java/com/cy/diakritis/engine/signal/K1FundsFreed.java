package com.cy.diakritis.engine.signal;

import com.cy.diakritis.engine.band.Weights;

/**
 * K1 — liquidation kill-chain: funds were recently freed (e.g. a term deposit broken) and are now
 * being moved out. When the freed-funds posture covers this payment the signal is
 * {@code min(1, freed/amount)} scaled by an exponential decay from the last freeing event.
 *
 * <p><b>Kill-chain horizon (contract).</b> Real liquidation scams break a deposit and then drain it
 * days later, so the funds-freed → K1 linkage uses a LONGER horizon than the velocity signals:
 * {@link Weights#POSTURE_FUNDS_FREED_WINDOW_HOURS} (7 days) rather than the
 * {@link Weights#POSTURE_VELOCITY_WINDOW_HOURS} (72h) burst window. The deposit-break event is
 * persisted in account posture, so K1 recognises "liquidation within the funds-freed window" even
 * when the drain happens well beyond the rolling 72h burst posture. The decay tau is tuned to the
 * funds-freed window so the linkage stays strong for several days and has fully relaxed by the edge.
 *
 * <p>Zero when nothing was freed, the freed amount does not cover the payment, or the last break is
 * older than the funds-freed window.
 */
public final class K1FundsFreed implements Signal {

    private static final double WINDOW_MS =
            Weights.POSTURE_FUNDS_FREED_WINDOW_HOURS * 60.0 * 60.0 * 1000.0;
    /** Decay tau chosen so the freed-funds link has fully relaxed by the funds-freed window edge. */
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
