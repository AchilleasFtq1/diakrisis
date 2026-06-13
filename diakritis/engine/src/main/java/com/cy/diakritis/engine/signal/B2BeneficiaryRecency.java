package com.cy.diakritis.engine.signal;

import com.cy.diakritis.engine.band.Weights;

import java.util.Optional;

/**
 * B2 — recency of the beneficiary relationship, decaying as {@code exp(-ageDays / tau)} from the
 * first-seen timestamp with {@code tau = }{@value Weights#B2_DECAY_TAU_DAYS} days. A relationship
 * established moments ago scores ~1.0 and fades toward 0 over months. A counterparty with no
 * history at all is treated as age 0 (brand-new identity) and scores 1.0.
 */
public final class B2BeneficiaryRecency implements Signal {

    @Override
    public String id() {
        return "B2";
    }

    @Override
    public double weight() {
        return Weights.B2;
    }

    @Override
    public double value(SignalContext ctx) {
        Optional<Long> firstSeen = ctx.store().firstSeenEpochMs(ctx.accountId(), ctx.cpKey());
        if (firstSeen.isEmpty()) {
            // No history → brand-new identity → age 0 → full recency.
            return 1.0;
        }
        double ageDays = SignalMath.ageDays(firstSeen.get(), ctx.now().toEpochMilli());
        return SignalMath.clamp01(SignalMath.expDecayDays(ageDays, Weights.B2_DECAY_TAU_DAYS));
    }
}
