package com.cy.diakritis.engine.band;

/**
 * Central registry of every signed signal weight and tuning constant used by the engine.
 *
 * <p>These are the single source of truth for the contribution of each band/signal and for the
 * thresholds that govern decay, escalation and windowing. They are named constants (no magic
 * numbers leak into signal or pipeline code) so QA can assert golden paths against exact values.
 */
public final class Weights {

    private Weights() {
    }

    // --- Beneficiary / counterparty-novelty band ---
    public static final int B1 = 14;
    public static final int B2 = 10;
    public static final int B3 = 8;
    public static final int B4 = -12; // negative: an established, trusted payee credits trust
    public static final int B5 = 16;

    // --- Payment-context band ---
    public static final int P1 = 22;

    // --- Amount-anomaly band ---
    public static final int A1 = 12;
    public static final int A2 = 18;
    public static final int A3 = 12;
    public static final int A4 = 6;

    // --- Velocity band ---
    public static final int V1 = 8;
    public static final int V2 = 10;

    // --- Channel band ---
    public static final int C1 = 6;
    public static final int C3 = 8;

    // --- Geo band ---
    public static final int G1 = 12;
    public static final int G2 = 6;

    // --- Device band ---
    public static final int D1 = 10;
    public static final int D2 = 6;

    // --- Kill-chain / liquidation band ---
    public static final int K1 = 16;
    public static final int K2 = 10;
    public static final int K3 = 8;

    // --- Mass-payment band ---
    public static final int MP1 = 16;
    public static final int MP2 = 12;
    public static final int MP4 = 14;

    // --- Model bands (contribution caps) ---
    public static final int M1_CAP = 18;
    public static final int M2_CAP = 12;

    // --- Cross-account band ---
    public static final int X1 = 20;

    // --- Tuning constants ---
    public static final int AI_ESCALATION_THRESHOLD = 80;
    public static final int TY2_ESTABLISHED_MIN_PAYMENTS = 3;
    public static final int TY2_ESTABLISHED_MIN_AGE_DAYS = 30;
    public static final int B2_DECAY_TAU_DAYS = 60;
    public static final double A2_DRAIN_TELL = 0.8;
    public static final int LOGICAL_AMOUNT_WINDOW_HOURS = 24;
    public static final int HOLD_DEFAULT_MINUTES = 30;
    public static final int X1_HALFLIFE_HOURS = 6;
}
