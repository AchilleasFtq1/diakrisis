package com.cy.diakritis.etl;

/**
 * Streaming mean/variance accumulator (Welford's online algorithm).
 *
 * <p>Operates on integer euro-cents so the running mean is computed in the same unit the feature
 * tables persist. Population standard deviation is used (n in the denominator) because the
 * aggregate is treated as the full observed history of a counterparty, not a sample of it.
 */
final class Welford {

    private long count;
    private double mean;
    private double m2;

    void add(long value) {
        count++;
        double delta = value - mean;
        mean += delta / count;
        double delta2 = value - mean;
        m2 += delta * delta2;
    }

    long count() {
        return count;
    }

    long meanRounded() {
        return Math.round(mean);
    }

    long stdRounded() {
        if (count < 2) {
            return 0L;
        }
        return Math.round(Math.sqrt(m2 / count));
    }
}
