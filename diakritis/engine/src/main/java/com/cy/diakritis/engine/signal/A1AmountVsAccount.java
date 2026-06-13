package com.cy.diakritis.engine.signal;

import com.cy.diakritis.engine.band.Weights;
import com.cy.diakritis.engine.store.AccountStatsView;

/**
 * A1 — the logical amount is anomalous versus the account's own outgoing behaviour. Uses a robust
 * z-score against the account's outgoing median/MAD (resistant to the heavy tails of real payment
 * data), then maps {@code clamp((z - 2) / 4)} into {@code [0,1]}. A payment many sigma above the
 * account's typical outflow registers here even if the payee is known.
 */
public final class A1AmountVsAccount implements Signal {

    @Override
    public String id() {
        return "A1";
    }

    @Override
    public double weight() {
        return Weights.A1;
    }

    @Override
    public double value(SignalContext ctx) {
        AccountStatsView stats = ctx.store().accountStats(ctx.accountId());
        if (stats == null || stats.outTxnCount() == 0) {
            return 0.0;
        }
        double z = SignalMath.robustZ(
                ctx.logicalAmountCents(),
                stats.outMedianAmountCents(),
                stats.outMadAmountCents(),
                stats.outStdAmountCents());
        return SignalMath.zToSignal(z);
    }
}
