package com.cy.diakritis.engine.signal;

import com.cy.diakritis.engine.band.Weights;

import java.util.Optional;

/**
 * B4 — established, trusted payee. Fires (1.0) when the relationship is deep (>= 20 prior payments)
 * or old (> 90 days). Its weight is negative ({@link Weights#B4}), so firing credits trust and pulls
 * the score down — this is what lets a large-but-routine payment to a long-standing supplier stay in
 * ALLOW.
 */
public final class B4EstablishedPayee implements Signal {

    private static final long ESTABLISHED_MIN_PAYMENTS = 20L;
    private static final double ESTABLISHED_MIN_AGE_DAYS = 90.0;

    @Override
    public String id() {
        return "B4";
    }

    @Override
    public double weight() {
        return Weights.B4;
    }

    @Override
    public double value(SignalContext ctx) {
        long payCount = ctx.store().priorPaymentCount(ctx.accountId(), ctx.cpKey());
        if (payCount >= ESTABLISHED_MIN_PAYMENTS) {
            return 1.0;
        }
        Optional<Long> firstSeen = ctx.store().firstSeenEpochMs(ctx.accountId(), ctx.cpKey());
        if (firstSeen.isPresent()) {
            double ageDays = SignalMath.ageDays(firstSeen.get(), ctx.now().toEpochMilli());
            if (ageDays > ESTABLISHED_MIN_AGE_DAYS) {
                return 1.0;
            }
        }
        return 0.0;
    }
}
