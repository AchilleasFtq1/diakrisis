package com.cy.diakritis.engine.signal;

import com.cy.diakritis.common.dto.MassPaymentPayload;
import com.cy.diakritis.engine.band.Weights;
import com.cy.diakritis.engine.store.AccountStatsView;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * MP2 — cadence / total anomaly (§4A): the batch total is out of line with the account's outgoing
 * history. A payroll run is rhythmic (similar total each cycle); a fan-out drain is an outsized,
 * one-off total. The signal is a robust z-score of the batch total against the account's outgoing
 * median/MAD baseline, mapped {@code clamp((z - MP2_Z_FLOOR) / 4)} into {@code [0,1]} — the same
 * robust-z shape A1 uses, lifted to the batch level.
 *
 * <p>Zero with no outgoing baseline (cold-start business account) or for a non-batch event.
 */
public final class MP2CadenceTotalAnomaly implements Signal {

    private static final double Z_SPAN = 4.0;

    @Override
    public String id() {
        return "MP2";
    }

    @Override
    public double weight() {
        return Weights.MP2;
    }

    @Override
    public double value(SignalContext ctx) {
        if (!(ctx.event().payload() instanceof MassPaymentPayload payload)) {
            return 0.0;
        }
        AccountStatsView stats = ctx.store().accountStats(ctx.accountId());
        if (stats == null || stats.outTxnCount() == 0) {
            return 0.0;
        }
        long totalCents = toCents(payload.totalEur());
        double z = SignalMath.robustZ(
                totalCents,
                stats.outMedianAmountCents(),
                stats.outMadAmountCents(),
                stats.outStdAmountCents());
        return SignalMath.clamp01((z - Weights.MP2_Z_FLOOR) / Z_SPAN);
    }

    private static long toCents(BigDecimal eur) {
        if (eur == null) {
            return 0L;
        }
        return eur.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
