package com.cy.diakritis.engine.signal;

import com.cy.diakritis.engine.band.Weights;
import com.cy.diakritis.engine.m1.M1Scorer;

/**
 * M1 — the supervised model signal. Its value is the M1 model's calibrated, percentile-ranked
 * fraud score in {@code [0,1]}; its contribution is capped at {@link Weights#M1_CAP} so a single
 * model can never dominate the rules-based bands. When the model is unavailable the scorer returns
 * 0 and this signal simply contributes nothing.
 */
public final class M1ModelSignal implements Signal {

    private final M1Scorer scorer;

    public M1ModelSignal(M1Scorer scorer) {
        this.scorer = scorer;
    }

    @Override
    public String id() {
        return "M1";
    }

    @Override
    public double weight() {
        return Weights.M1_CAP;
    }

    @Override
    public double value(SignalContext ctx) {
        return SignalMath.clamp01(scorer.score(ctx));
    }
}
