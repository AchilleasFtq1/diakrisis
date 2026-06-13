package com.cy.diakritis.engine.signal;

import com.cy.diakritis.engine.band.Weights;
import com.cy.diakritis.engine.m2.M2Scorer;

/**
 * M2 — vector-similarity fraud-neighbor share (§9.2). Its value is the distance-weighted fraud share
 * among the k nearest exemplars to this action's standardized feature vector, in {@code [0,1]}; its
 * contribution is capped at {@link Weights#M2_CAP} so a similarity signal can never dominate the
 * rules-based bands. When the exemplar index is unavailable the scorer returns 0 and M2 contributes
 * nothing — M2 is additive, never a hard dependency.
 */
public final class M2ExemplarSignal implements Signal {

    private final M2Scorer scorer;

    public M2ExemplarSignal(M2Scorer scorer) {
        this.scorer = scorer;
    }

    @Override
    public String id() {
        return "M2";
    }

    @Override
    public double weight() {
        return Weights.M2_CAP;
    }

    @Override
    public double value(SignalContext ctx) {
        if (scorer == null) {
            return 0.0;
        }
        return SignalMath.clamp01(scorer.score(ctx));
    }
}
