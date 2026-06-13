package com.cy.diakritis.engine.signal;

import com.cy.diakritis.engine.band.Weights;

/**
 * A2 — account-drain tell: what fraction of the available balance this logical amount moves.
 * Computed as {@code clamp((amount/available - 0.5) / 0.55)}: a payment that takes half the balance
 * starts to register and one that sweeps ~100% saturates (≈0.974 of balance → ≈0.86). Sweeping the
 * account is the signature of liquidation / safe-account scams. Zero when no balance is known.
 */
public final class A2BalanceDrain implements Signal {

    private static final double DRAIN_FLOOR = 0.5;
    private static final double DRAIN_SPAN = 0.55;

    @Override
    public String id() {
        return "A2";
    }

    @Override
    public double weight() {
        return Weights.A2;
    }

    @Override
    public double value(SignalContext ctx) {
        long available = ctx.availableBalanceCents();
        if (available <= 0) {
            return 0.0;
        }
        double fraction = (double) ctx.logicalAmountCents() / (double) available;
        return SignalMath.clamp01((fraction - DRAIN_FLOOR) / DRAIN_SPAN);
    }
}
