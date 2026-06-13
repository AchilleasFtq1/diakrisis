package com.cy.diakritis.engine.signal;

import com.cy.diakritis.engine.band.Weights;

/**
 * V1 — burst velocity: how fast this account is acting relative to a calm baseline. A normal retail
 * account makes a handful of payments a week; a coached-scam session or a mule fan-out spikes to
 * many actions an hour. The rate is the account's recent actions-per-hour from runtime state
 * (including the current event, which the pipeline records first), mapped linearly onto {@code [0,1]}
 * with saturation at {@link Weights#V1_BURST_PER_HOUR_SATURATION} actions/hour.
 *
 * <p>V1 stays on the SHORT velocity horizon ({@link Weights#POSTURE_VELOCITY_WINDOW_HOURS}) on
 * purpose: a burst is only a burst while it is happening, unlike the long funds-freed kill-chain
 * linkage K1 reasons over.
 */
public final class V1BurstVelocity implements Signal {

    @Override
    public String id() {
        return "V1";
    }

    @Override
    public double weight() {
        return Weights.V1;
    }

    @Override
    public double value(SignalContext ctx) {
        double perHour = ctx.runtime().actionsPerHour(ctx.accountId(), ctx.now().toEpochMilli());
        double saturation = Weights.V1_BURST_PER_HOUR_SATURATION;
        if (saturation <= 0) {
            return 0.0;
        }
        // A single action (perHour ~1 over the floored 1h window) is not yet a burst; scale from there.
        double normalized = (perHour - 1.0) / (saturation - 1.0);
        return SignalMath.clamp01(normalized);
    }
}
