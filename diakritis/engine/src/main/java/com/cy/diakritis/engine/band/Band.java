package com.cy.diakritis.engine.band;

/**
 * The four monotone risk bands the raw score maps into, ordered least-to-most restrictive.
 * {@link #ordinal()} ordering is relied upon by {@code min}/escalation logic, so the
 * declaration order is load-bearing.
 */
public enum Band {
    ALLOW,
    CONFIRM,
    HOLD,
    BLOCK
}
