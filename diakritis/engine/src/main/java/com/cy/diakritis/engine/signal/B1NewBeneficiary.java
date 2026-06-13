package com.cy.diakritis.engine.signal;

import com.cy.diakritis.engine.band.Weights;

/**
 * B1 — first-ever payment to this counterparty. Fires (1.0) when the account has no prior payment
 * history to the resolved counterparty key; a brand-new payee is the single strongest structural
 * tell across authorised-push-payment scams.
 */
public final class B1NewBeneficiary implements Signal {

    @Override
    public String id() {
        return "B1";
    }

    @Override
    public double weight() {
        return Weights.B1;
    }

    @Override
    public double value(SignalContext ctx) {
        long prior = ctx.store().priorPaymentCount(ctx.accountId(), ctx.cpKey());
        return prior == 0 ? 1.0 : 0.0;
    }
}
