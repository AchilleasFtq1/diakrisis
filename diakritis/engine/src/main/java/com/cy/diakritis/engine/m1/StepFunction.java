package com.cy.diakritis.engine.m1;

import java.util.Arrays;

/**
 * A non-decreasing step function defined by sorted (threshold, value) breakpoints, evaluated by
 * binary search. Used both for the isotonic recalibration of the raw model probability and for
 * percentile-ranking the calibrated score against the validation distribution.
 *
 * <p>For a query {@code x} the value of the greatest threshold {@code <= x} is returned; below the
 * first threshold the first value is returned (the function is clamped at its left edge).
 */
final class StepFunction {

    private final double[] thresholds;
    private final double[] values;

    StepFunction(double[] thresholds, double[] values) {
        if (thresholds.length != values.length) {
            throw new IllegalArgumentException("thresholds and values must be the same length");
        }
        if (thresholds.length == 0) {
            throw new IllegalArgumentException("step function requires at least one breakpoint");
        }
        this.thresholds = thresholds.clone();
        this.values = values.clone();
    }

    /** Value of the greatest threshold {@code <= x}; the left-most value below the first threshold. */
    double apply(double x) {
        int idx = Arrays.binarySearch(thresholds, x);
        if (idx >= 0) {
            return values[idx];
        }
        // insertion point: first element greater than x is at -idx-1, so the last <= x is at -idx-2.
        int lastLeq = -idx - 2;
        if (lastLeq < 0) {
            return values[0];
        }
        return values[lastLeq];
    }
}
