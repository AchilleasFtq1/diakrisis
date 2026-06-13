package com.cy.diakritis.engine.m1;

/**
 * Percentile-ranks a calibrated score against the validation-set score distribution stored as
 * {@code (percentile 0-100 → score)} breakpoints (ascending score). The returned rank in
 * {@code [0,1]} is the fraction of validation scores at or below the query, i.e. the highest
 * percentile whose breakpoint score is {@code <=} the query, divided by 100.
 *
 * <p>This turns a raw calibrated probability into a population-relative risk position, which is
 * what the M1 signal contributes (a score in the 0.97 percentile is "riskier than 97% of traffic").
 */
final class PercentileRanker {

    private final double[] percentile; // ascending 0..100
    private final double[] score;       // ascending, aligned to percentile

    PercentileRanker(double[] percentile, double[] score) {
        if (percentile.length != score.length || percentile.length == 0) {
            throw new IllegalArgumentException("percentile and score arrays must be equal, non-empty length");
        }
        this.percentile = percentile.clone();
        this.score = score.clone();
    }

    /** Percentile rank in {@code [0,1]} of {@code calibratedScore} against the stored distribution. */
    double rank(double calibratedScore) {
        // Below the lowest breakpoint → rank 0; at/above the top → rank percentile_max/100 (≈1).
        if (calibratedScore < score[0]) {
            return percentile[0] / 100.0;
        }
        int chosen = 0;
        // Linear scan over ~101 breakpoints — trivially cheap and avoids edge cases of duplicate
        // scores that a binary search would have to disambiguate.
        for (int i = 0; i < score.length; i++) {
            if (score[i] <= calibratedScore) {
                chosen = i;
            } else {
                break;
            }
        }
        return percentile[chosen] / 100.0;
    }
}
