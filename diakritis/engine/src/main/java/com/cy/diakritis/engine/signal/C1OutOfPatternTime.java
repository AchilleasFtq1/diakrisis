package com.cy.diakritis.engine.signal;

import com.cy.diakritis.engine.band.Weights;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * C1 — out-of-pattern time. Berka carries weekday habit (dates, no intraday clock), so the weekday
 * baseline is the account's observed days-of-week; the hour-of-day habit is learned at runtime via
 * the observation store (kinds {@code "DOW"} and {@code "HOUR"}). The signal fires when the current
 * action lands on a weekday and/or hour the account has a baseline for but has never used.
 *
 * <p>Cold start is honest: with no observed DOW/HOUR history the account has no pattern to deviate
 * from, so C1 is 0 (the engine leans on typologies + M1 for thin-history accounts, per §6).
 */
public final class C1OutOfPatternTime implements Signal {

    private static final String KIND_DOW = "DOW";
    private static final String KIND_HOUR = "HOUR";
    /** Each dimension (weekday, hour) that is out of pattern contributes half the signal. */
    private static final double DIMENSION_WEIGHT = 0.5;

    @Override
    public String id() {
        return "C1";
    }

    @Override
    public double weight() {
        return Weights.C1;
    }

    @Override
    public double value(SignalContext ctx) {
        Instant ts = ctx.event().context() != null ? ctx.event().context().ts() : ctx.now();
        ZonedDateTime zoned = ts.atZone(ZoneOffset.UTC);
        String dow = Integer.toString(zoned.getDayOfWeek().getValue()); // 1..7
        String hour = Integer.toString(zoned.getHour());                // 0..23

        double score = 0.0;
        score += outOfPattern(ctx, KIND_DOW, dow) ? DIMENSION_WEIGHT : 0.0;
        score += outOfPattern(ctx, KIND_HOUR, hour) ? DIMENSION_WEIGHT : 0.0;
        return SignalMath.clamp01(score);
    }

    /**
     * Out of pattern when the account has a baseline of values of this kind (weekday habit from Berka,
     * hour habit learned at runtime) but the current value is not among them. No baseline → not out of
     * pattern (cold start scores 0).
     */
    private static boolean outOfPattern(SignalContext ctx, String kind, String value) {
        if (!ctx.obs().hasAnyOfKind(ctx.accountId(), kind)) {
            return false;
        }
        List<String> seen = ctx.obs().distinctValuesOfKind(ctx.accountId(), kind);
        return !seen.contains(value);
    }
}
