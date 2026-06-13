package com.cy.diakritis.engine.signal;

import com.cy.diakritis.common.dto.BatchItem;
import com.cy.diakritis.common.dto.MassPaymentPayload;
import com.cy.diakritis.engine.band.Weights;

import java.util.List;

/**
 * MP1 — new-counterparty share (§4A): the fraction of batch lines paying a counterparty the account
 * has no history with. A real payroll run is ≈0 (the same employees every month); a mule fan-out is
 * ≈1 (30 fresh destinations in one file). The signal is that fraction directly, saturating at
 * {@link Weights#MP1_SHARE_SATURATION}.
 *
 * <p>Only meaningful on a {@code MASS_PAYMENT}; on any other event type it scores 0.
 */
public final class MP1NewCounterpartyShare implements Signal {

    @Override
    public String id() {
        return "MP1";
    }

    @Override
    public double weight() {
        return Weights.MP1;
    }

    @Override
    public double value(SignalContext ctx) {
        if (!(ctx.event().payload() instanceof MassPaymentPayload payload)) {
            return 0.0;
        }
        List<BatchItem> items = payload.items();
        if (items == null || items.isEmpty()) {
            return 0.0;
        }
        int newCount = 0;
        for (BatchItem item : items) {
            String cpKey = Identity.counterpartyKey(item.counterparty());
            if (ctx.store().priorPaymentCount(ctx.accountId(), cpKey) == 0) {
                newCount++;
            }
        }
        double share = (double) newCount / (double) items.size();
        double saturation = Weights.MP1_SHARE_SATURATION;
        if (saturation <= 0) {
            return 0.0;
        }
        return SignalMath.clamp01(share / saturation);
    }
}
