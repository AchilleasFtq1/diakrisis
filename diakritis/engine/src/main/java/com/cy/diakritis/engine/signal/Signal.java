package com.cy.diakritis.engine.signal;

/**
 * A single scoring signal. Each signal contributes {@code weight * value} to the raw score,
 * where {@link #value(SignalContext)} is bounded to {@code [0,1]} (it is a normalised strength,
 * never a raw contribution) and {@link #weight()} is the signed band weight from
 * {@link com.cy.diakritis.engine.band.Weights}.
 */
public interface Signal {

    /** Stable signal identifier (e.g. {@code "B1"}), emitted in the audit trail. */
    String id();

    /** Signed weight applied to the value to produce this signal's contribution. */
    double weight();

    /** Normalised signal strength in {@code [0,1]} for the given context. */
    double value(SignalContext ctx);
}
