package com.cy.diakritis.engine.pipeline;

/**
 * Reason-code constants stamped onto the engine verdict and propagated to the combined decision.
 * They are the stable machine-readable label for "why" behind each outcome.
 */
public final class ReasonCodes {

    private ReasonCodes() {
    }

    /** Liquidation kill-chain typology. */
    public static final String KILLCHAIN = "DKR-KILLCHAIN";
    /** Invoice-redirection typology. */
    public static final String INVOICE = "DKR-INVOICE";
    /** Cross-account / X1 pattern. */
    public static final String XACCT = "DKR-XACCT";
    /** Safe-account scam typology. */
    public static final String SAFE_ACCOUNT = "DKR-SAFEACCT";
    /** Generic elevated-risk fraud signal (no named typology). */
    public static final String FRAUD = "DKR-FRAD";
    /** Clean allow — no risk reason; MS03 (the "no message" sentinel) is intentionally absent. */
    public static final String NONE = null;
}
