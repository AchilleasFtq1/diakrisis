package com.cy.diakritis.engine.signal;

import com.cy.diakritis.common.persistence.RecentPayment;
import com.cy.diakritis.engine.band.Weights;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * V2 — monotonically rising payments to the same counterparty across distinct days. The grooming
 * escalation of a romance / investment scam shows up as steadily increasing transfers to one payee
 * over multiple days. Fires (1.0) when the recent payments to {@code cpKey} are strictly
 * non-decreasing (with at least one genuine rise) and span at least two distinct calendar days.
 */
public final class V2RisingAmounts implements Signal {

    private static final int MIN_DISTINCT_DAYS = 2;

    @Override
    public String id() {
        return "V2";
    }

    @Override
    public double weight() {
        return Weights.V2;
    }

    @Override
    public double value(SignalContext ctx) {
        List<RecentPayment> recent = ctx.store().recentPayments(ctx.accountId(), ctx.cpKey());
        if (recent == null || recent.size() < 2) {
            return 0.0;
        }

        // recentPayments is stored oldest-to-newest; verify it is sorted by time and rising.
        Set<Long> distinctDays = new LinkedHashSet<>();
        long previousAmount = Long.MIN_VALUE;
        boolean sawIncrease = false;

        for (RecentPayment p : recent) {
            if (p.getAmountCents() < previousAmount) {
                return 0.0; // not monotonic
            }
            if (previousAmount != Long.MIN_VALUE && p.getAmountCents() > previousAmount) {
                sawIncrease = true;
            }
            previousAmount = p.getAmountCents();
            distinctDays.add(epochDay(p.getEpochMs()));
        }

        boolean risesAcrossDays = sawIncrease && distinctDays.size() >= MIN_DISTINCT_DAYS;
        return risesAcrossDays ? 1.0 : 0.0;
    }

    private static long epochDay(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay();
    }
}
