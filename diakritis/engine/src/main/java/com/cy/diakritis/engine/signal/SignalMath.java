package com.cy.diakritis.engine.signal;

/**
 * Shared numeric helpers for signals: clamping, robust-z scoring and time conversions.
 * Kept deliberately tiny and side-effect free so signal code stays declarative.
 */
public final class SignalMath {

    /** Consistency constant making MAD a consistent estimator of the standard deviation. */
    private static final double MAD_TO_SIGMA = 1.4826;
    private static final double MS_PER_DAY = 24.0 * 60.0 * 60.0 * 1000.0;
    private static final double Z_FLOOR = 2.0;
    private static final double Z_SPAN = 4.0;

    private SignalMath() {
    }

    /** Clamp {@code v} into {@code [0,1]}. */
    public static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        if (v < 0.0) {
            return 0.0;
        }
        return Math.min(v, 1.0);
    }

    /**
     * Robust z-score of {@code valueCents} against a median/MAD baseline. MAD is scaled by
     * {@value #MAD_TO_SIGMA} to approximate a standard deviation. When MAD is zero (degenerate /
     * too-tight baseline) the std is used as a fallback; if both are zero the z is 0.
     */
    public static double robustZ(double valueCents, double medianCents, double madCents, double stdCents) {
        double scale = madCents > 0 ? madCents * MAD_TO_SIGMA : stdCents;
        if (scale <= 0) {
            return 0.0;
        }
        return (valueCents - medianCents) / scale;
    }

    /**
     * Map a robust-z onto {@code [0,1]} via {@code clamp((z - 2) / 4)}: anomalies start to register
     * around 2 sigma and saturate around 6 sigma.
     */
    public static double zToSignal(double z) {
        return clamp01((z - Z_FLOOR) / Z_SPAN);
    }

    /** Age in (fractional) days between two epoch-millis instants, floored at 0. */
    public static double ageDays(long fromEpochMs, long toEpochMs) {
        double days = (toEpochMs - fromEpochMs) / MS_PER_DAY;
        return Math.max(0.0, days);
    }

    /** Exponential decay {@code exp(-t/tau)} with {@code t} and {@code tau} both in days. */
    public static double expDecayDays(double ageDays, double tauDays) {
        if (tauDays <= 0) {
            return 0.0;
        }
        return Math.exp(-ageDays / tauDays);
    }
}
