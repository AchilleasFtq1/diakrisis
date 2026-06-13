package com.cy.diakritis.engine.signal;

import com.cy.diakritis.engine.band.Weights;

/**
 * K3 — beneficiary-add burst: several new payees were added within the lookback window
 * ({@link Weights#POSTURE_BENEFICIARY_ADD_WINDOW_HOURS}). One new payee is routine; a flurry of them
 * is the set-up phase of a mule fan-out or a coached-scam sweep. The signal registers from
 * {@link Weights#K3_ADD_BURST_MIN} adds and saturates at {@link Weights#K3_ADD_BURST_SATURATION},
 * reading the count from account posture.
 *
 * <p>Zero below the minimum add count.
 */
public final class K3BeneficiaryAddBurst implements Signal {

    @Override
    public String id() {
        return "K3";
    }

    @Override
    public double weight() {
        return Weights.K3;
    }

    @Override
    public double value(SignalContext ctx) {
        long adds = ctx.posture().beneficiaryAddCount72h();
        if (adds < Weights.K3_ADD_BURST_MIN) {
            return 0.0;
        }
        int min = Weights.K3_ADD_BURST_MIN;
        int saturation = Weights.K3_ADD_BURST_SATURATION;
        if (saturation <= min) {
            return 1.0;
        }
        double normalized = (double) (adds - min) / (double) (saturation - min);
        return SignalMath.clamp01(normalized);
    }
}
