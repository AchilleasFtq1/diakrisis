package com.cy.diakritis.engine.signal;

import com.cy.diakritis.common.dto.MassPaymentPayload;
import com.cy.diakritis.engine.band.Weights;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * MP4 — batch drain (§4A): what fraction of the available balance the whole batch moves. A fan-out
 * that sweeps the account in one file is the mule-drain signature; it also interacts with K1 (freed
 * funds being swept out as a batch). Computed as {@code clamp((total/available - MP4_DRAIN_FLOOR) /
 * MP4_DRAIN_SPAN)} — the batch-level twin of A2's account-drain tell.
 *
 * <p>Zero when no balance is known or the event is not a batch.
 */
public final class MP4BatchDrain implements Signal {

    @Override
    public String id() {
        return "MP4";
    }

    @Override
    public double weight() {
        return Weights.MP4;
    }

    @Override
    public double value(SignalContext ctx) {
        if (!(ctx.event().payload() instanceof MassPaymentPayload payload)) {
            return 0.0;
        }
        long available = toCents(payload.availableBalanceEur());
        if (available <= 0) {
            return 0.0;
        }
        // Derive the batch total from the actual line items rather than the client-declared totalEur:
        // an attacker who understates totalEur could otherwise hide a full drain from MP4 while the
        // per-line amounts still execute. The lines are the money that actually moves.
        long total = batchTotalCents(payload);
        double fraction = (double) total / (double) available;
        double span = Weights.MP4_DRAIN_SPAN;
        if (span <= 0) {
            return 0.0;
        }
        return SignalMath.clamp01((fraction - Weights.MP4_DRAIN_FLOOR) / span);
    }

    /** The batch total as the sum of the line amounts actually being executed (in euro-cents). */
    private static long batchTotalCents(MassPaymentPayload payload) {
        long sum = 0L;
        if (payload.items() != null) {
            for (var item : payload.items()) {
                sum += toCents(item.amountEur());
            }
        }
        return sum;
    }

    private static long toCents(BigDecimal eur) {
        if (eur == null) {
            return 0L;
        }
        return eur.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
