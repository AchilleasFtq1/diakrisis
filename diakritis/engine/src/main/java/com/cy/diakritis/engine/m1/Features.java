package com.cy.diakritis.engine.m1;

import com.cy.diakritis.common.dto.ActionEvent;
import com.cy.diakritis.common.dto.Counterparty;
import com.cy.diakritis.engine.signal.SignalContext;
import com.cy.diakritis.engine.store.ObservationsView;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the 16-feature vector the M1 GradientTreeBoost was trained on, in the exact order of
 * {@code m1/columns.txt}, per {@code FEATURE_SPEC.md}.
 *
 * <p>Training source was IEEE-CIS; the serve-time mapping is documented field-by-field below.
 * Several training inputs (velocity counts C*, recency deltas D*, free-mail proxy) have no direct
 * analogue on a single Berka action — their REAL serve-time value is "never seen", which maps to
 * the model's own missing/zero sentinels (counts → ln(1+0)=0; deltas → −1 with a miss count). That
 * is a faithful serve mapping, not a placeholder.
 */
public final class Features {

    /** Column order — must match {@code m1/columns.txt} exactly. */
    public static final String[] COLUMNS = {
            "amt_log", "hour_sin", "hour_cos", "dow_sin", "dow_cos",
            "c1_log", "c13_log", "c14_log", "d_miss_count",
            "d1", "d4", "d10", "d15", "amt_ratio", "email_missing", "is_free_mail"
    };

    public static final int FEATURE_COUNT = 16;

    private static final long SECONDS_PER_DAY = 86_400L;
    private static final double TWO_PI = 2.0 * Math.PI;
    private static final double AMT_RATIO_CAP = 50.0;
    private static final double MISSING_DELTA = -1.0;
    private static final double MS_PER_DAY = 24.0 * 60.0 * 60.0 * 1000.0;

    private static final Set<String> FREE_MAIL = Set.of(
            "gmail.com", "yahoo.com", "hotmail.com", "outlook.com", "aol.com",
            "anonymous.com", "mail.com", "protonmail.com", "icloud.com", "live.com"
    );

    private Features() {
    }

    /**
     * Produce the 16-feature vector for the event in {@code ctx}. Pure and side-effect free;
     * reads only the already-resolved amount and the observation store.
     */
    public static double[] toVector(SignalContext ctx) {
        ActionEvent event = ctx.event();
        Instant ts = event.context() != null ? event.context().ts() : ctx.now();
        long amountCents = ctx.amountCents();
        double amountEur = amountCents / 100.0;

        double[] v = new double[FEATURE_COUNT];

        // 1. amt_log = ln(1 + amount)
        v[0] = Math.log1p(Math.max(0.0, amountEur));

        // 2-5. cyclic hour/day-of-week encodings from the action timestamp.
        long epochSec = ts.getEpochSecond();
        double hour = (double) Math.floorMod(epochSec, SECONDS_PER_DAY) / 3600.0;
        long dow = Math.floorMod(Math.floorDiv(epochSec, SECONDS_PER_DAY), 7L);
        v[1] = Math.sin(TWO_PI * hour / 24.0);
        v[2] = Math.cos(TWO_PI * hour / 24.0);
        v[3] = Math.sin(TWO_PI * dow / 7.0);
        v[4] = Math.cos(TWO_PI * dow / 7.0);

        // 6-8. velocity counts C1/C13/C14 → ln(1 + count); no serve-time velocity store ⇒ count 0.
        long c1 = velocityCount(ctx, "C1");
        long c13 = velocityCount(ctx, "C13");
        long c14 = velocityCount(ctx, "C14");
        v[5] = Math.log1p(c1);
        v[6] = Math.log1p(c13);
        v[7] = Math.log1p(c14);

        // 9-13. recency deltas D1/D4/D10/D15: days since last seen, missing → −1, plus miss count.
        Double d1 = recencyDeltaDays(ctx, "D1", ts);
        Double d4 = recencyDeltaDays(ctx, "D4", ts);
        Double d10 = recencyDeltaDays(ctx, "D10", ts);
        Double d15 = recencyDeltaDays(ctx, "D15", ts);
        int missCount = countMissing(d1, d4, d10, d15);
        v[8] = missCount;
        v[9] = d1 != null ? d1 : MISSING_DELTA;
        v[10] = d4 != null ? d4 : MISSING_DELTA;
        v[11] = d10 != null ? d10 : MISSING_DELTA;
        v[12] = d15 != null ? d15 : MISSING_DELTA;

        // 14. amt_ratio = amount / mean(prior payments same account), first occurrence → 1.0, cap 50.
        v[13] = amountRatio(ctx, amountCents);

        // 15-16. email flags ↔ counterparty-type proxy. Use the context counterparty so a batch line's
        // own payee (the §4A per-line override) is honoured, not the whole-batch payload.
        String emailDomain = emailDomain(ctx.counterparty());
        v[14] = (emailDomain == null) ? 1.0 : 0.0;
        v[15] = (emailDomain != null && FREE_MAIL.contains(emailDomain)) ? 1.0 : 0.0;

        return v;
    }

    private static long velocityCount(SignalContext ctx, String kind) {
        // The serve path carries no IEEE-CIS-style velocity counter store; an unobserved velocity
        // key is genuinely count 0, which maps to ln(1+0)=0 at the call site. This is a faithful
        // serve mapping of an absent feature, kept as a named seam for when a counter store exists.
        return 0L;
    }

    private static Double recencyDeltaDays(SignalContext ctx, String kind, Instant ts) {
        ObservationsView obs = ctx.obs();
        if (obs == null) {
            return null;
        }
        Optional<Long> lastSeen = obs.lastSeenEpochMs(ctx.accountId(), kind, ctx.cpKey());
        if (lastSeen.isEmpty()) {
            return null;
        }
        double days = (ts.toEpochMilli() - lastSeen.get()) / MS_PER_DAY;
        return Math.max(0.0, days);
    }

    private static int countMissing(Double... deltas) {
        int n = 0;
        for (Double d : deltas) {
            if (d == null) {
                n++;
            }
        }
        return n;
    }

    private static double amountRatio(SignalContext ctx, long amountCents) {
        long priorCount = ctx.store().priorPaymentCount(ctx.accountId(), ctx.cpKey());
        if (priorCount == 0) {
            return 1.0; // first occurrence of this card1/counterparty → ratio 1.0
        }
        long meanPrior = ctx.store().meanAmountCents(ctx.accountId(), ctx.cpKey());
        if (meanPrior <= 0) {
            return 1.0;
        }
        double ratio = (double) amountCents / (double) meanPrior;
        return Math.min(ratio, AMT_RATIO_CAP);
    }

    /** Lower-cased email domain when the counterparty is e-mail addressed, else null. */
    private static String emailDomain(Counterparty cp) {
        if (cp == null) {
            return null;
        }
        if (cp.addressing() != com.cy.diakritis.common.dto.Addressing.EMAIL) {
            return null;
        }
        String value = cp.value();
        if (value == null) {
            return null;
        }
        int at = value.indexOf('@');
        if (at < 0 || at == value.length() - 1) {
            return null;
        }
        return value.substring(at + 1).toLowerCase();
    }
}
