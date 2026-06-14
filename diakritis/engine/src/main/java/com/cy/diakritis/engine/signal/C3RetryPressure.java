package com.cy.diakritis.engine.signal;

import com.cy.diakritis.engine.band.Weights;

/**
 * C3 — retry pressure: the same session is re-submitting at successively higher amounts, probing for
 * where a limit or control gives way. A coached victim told to "try again, send more" produces this
 * server-side raised-amount pattern. The signal is the count of strictly-increasing retry steps in
 * the session (from runtime state), scaled to saturate at {@link Weights#C3_RETRY_SATURATION} raises.
 *
 * <p>Zero when the session made fewer than two attempts or never raised the amount.
 */
public final class C3RetryPressure implements Signal {

    @Override
    public String id() {
        return "C3";
    }

    @Override
    public double weight() {
        return Weights.C3;
    }

    @Override
    public double value(SignalContext ctx) {
        String sessionId = ctx.event().context() == null ? null : ctx.event().context().sessionId();
        int raises = ctx.runtime().raisedAttemptCount(sessionId, ctx.now().toEpochMilli());
        if (raises <= 0) {
            return 0.0;
        }
        double saturation = Weights.C3_RETRY_SATURATION;
        if (saturation <= 0) {
            return 0.0;
        }
        return SignalMath.clamp01(raises / saturation);
    }
}
