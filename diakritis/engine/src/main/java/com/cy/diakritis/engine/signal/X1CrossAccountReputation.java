package com.cy.diakritis.engine.signal;

import com.cy.diakritis.common.dto.Verdict;
import com.cy.diakritis.engine.band.Weights;

import java.util.Optional;

/**
 * X1 — cross-account counterparty reputation (§4B, the network moat). The destination counterparty
 * was flagged (HELD / BLOCKED / cancelled-hold) on ANOTHER account recently; X1 fires on the third
 * victim before they finish typing, even though for them the payee is new and the amount
 * unremarkable. No single-account tool can produce this — it needs the shared {@code ReputationView}.
 *
 * <p>The signal decays exponentially from the last flag with a half-life of
 * {@link Weights#X1_HALFLIFE_HOURS} hours (a fresh flag scores ~1.0, fading over the day), scaled by
 * the severity of the worst outcome: a confirmed BLOCK weighs full, a HOLD / REQUIRE_APPROVAL a
 * little less. Zero when the counterparty has no flag on record.
 */
public final class X1CrossAccountReputation implements Signal {

    private static final double LN2 = Math.log(2.0);
    private static final double MS_PER_HOUR = 60.0 * 60.0 * 1000.0;
    /** A merely-held / pending counterparty weighs slightly less than a confirmed block. */
    private static final double HOLD_SEVERITY = 0.85;

    @Override
    public String id() {
        return "X1";
    }

    @Override
    public double weight() {
        return Weights.X1;
    }

    @Override
    public double value(SignalContext ctx) {
        if (ctx.reputation() == null) {
            return 0.0;
        }
        Optional<Long> lastFlag = ctx.reputation().lastFlagEpochMs(ctx.cpKey());
        if (lastFlag.isEmpty()) {
            return 0.0;
        }
        double ageMs = ctx.now().toEpochMilli() - lastFlag.get();
        if (ageMs < 0) {
            ageMs = 0;
        }
        double halfLifeMs = Weights.X1_HALFLIFE_HOURS * MS_PER_HOUR;
        if (halfLifeMs <= 0) {
            return 0.0;
        }
        double decay = Math.exp(-ageMs * LN2 / halfLifeMs);
        double severity = severity(ctx.reputation().worstOutcome(ctx.cpKey()));
        return SignalMath.clamp01(decay * severity);
    }

    /** Severity multiplier from the worst recorded outcome: BLOCK full, HOLD/approval a touch less. */
    private static double severity(Optional<String> worstOutcome) {
        if (worstOutcome.isEmpty()) {
            return 1.0;
        }
        Verdict verdict = parse(worstOutcome.get());
        if (verdict == Verdict.BLOCK) {
            return 1.0;
        }
        return HOLD_SEVERITY;
    }

    private static Verdict parse(String name) {
        if (name == null) {
            return null;
        }
        try {
            return Verdict.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
