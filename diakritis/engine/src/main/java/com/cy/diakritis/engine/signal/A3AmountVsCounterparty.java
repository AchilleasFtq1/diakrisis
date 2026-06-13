package com.cy.diakritis.engine.signal;

import com.cy.diakritis.common.persistence.RecentPayment;
import com.cy.diakritis.engine.band.Weights;

import java.util.List;

/**
 * A3 — the logical amount is anomalous versus what this account usually pays *this specific*
 * counterparty. Robust z-score against the counterparty's own mean and a MAD derived from its
 * recent payments, mapped {@code clamp((z - 2) / 4)}. With no prior history to the counterparty
 * the signal is 0 (B1/B2 already carry the novelty). This is the "you normally pay your supplier
 * €130, why €700?" tell.
 */
public final class A3AmountVsCounterparty implements Signal {

    private static final double MAD_TO_SIGMA = 1.4826;

    @Override
    public String id() {
        return "A3";
    }

    @Override
    public double weight() {
        return Weights.A3;
    }

    @Override
    public double value(SignalContext ctx) {
        long priorCount = ctx.store().priorPaymentCount(ctx.accountId(), ctx.cpKey());
        if (priorCount == 0) {
            return 0.0;
        }
        long mean = ctx.store().meanAmountCents(ctx.accountId(), ctx.cpKey());
        if (mean <= 0) {
            return 0.0;
        }
        List<RecentPayment> recent = ctx.store().recentPayments(ctx.accountId(), ctx.cpKey());
        double scale = scaleCents(recent, mean);
        if (scale <= 0) {
            return 0.0;
        }
        double z = (ctx.logicalAmountCents() - mean) / scale;
        return SignalMath.zToSignal(z);
    }

    /**
     * Dispersion scale for the counterparty: MAD of recent payments about their median (scaled to
     * sigma) when enough samples exist, otherwise a conservative fraction of the mean so a single
     * outsized payment to a low-variance payee can still register.
     */
    private static double scaleCents(List<RecentPayment> recent, long mean) {
        if (recent != null && recent.size() >= 2) {
            long[] amounts = recent.stream().mapToLong(RecentPayment::getAmountCents).sorted().toArray();
            double median = median(amounts);
            long[] deviations = new long[amounts.length];
            for (int i = 0; i < amounts.length; i++) {
                deviations[i] = Math.abs(amounts[i] - (long) median);
            }
            java.util.Arrays.sort(deviations);
            double mad = median(deviations);
            if (mad > 0) {
                return mad * MAD_TO_SIGMA;
            }
        }
        // Fallback: treat 25% of the mean as one sigma for a tight, low-sample payee.
        return mean * 0.25;
    }

    private static double median(long[] sorted) {
        int n = sorted.length;
        if (n == 0) {
            return 0.0;
        }
        if ((n & 1) == 1) {
            return sorted[n / 2];
        }
        return (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }
}
