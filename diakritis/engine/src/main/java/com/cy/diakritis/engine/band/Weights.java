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

    // --- Kill-chain horizon: NAMED posture windows -------------------------------------------
    // Velocity/burst signals reason over the short rolling-runtime window: a burst is only a
    // burst if it is happening now. Liquidation kill-chains, by contrast, routinely break a
    // deposit and then drain it DAYS later — so the funds-freed → K1 linkage is given a much
    // longer horizon than the velocity signals. K1 reads the persisted deposit-break event from
    // account posture across the whole funds-freed window, even beyond the 72h rolling posture.
    /** Burst / velocity (V1) horizon in hours — "is this happening right now?". */
    public static final int POSTURE_VELOCITY_WINDOW_HOURS = 72;
    /** Funds-freed → K1 liquidation-linkage horizon in hours (7 days). A broken deposit can be
     *  drained up to a week later; the kill-chain must still recognise the linkage. */
    public static final int POSTURE_FUNDS_FREED_WINDOW_HOURS = 168;
    /** Limit-raise (K2) lookback horizon in hours. */
    public static final int POSTURE_LIMIT_RAISED_WINDOW_HOURS = 72;
    /** Beneficiary-add-burst (K3) lookback horizon in hours. */
    public static final int POSTURE_BENEFICIARY_ADD_WINDOW_HOURS = 72;

    // --- Signal tuning constants -------------------------------------------------------------
    /** A4: an amount within this fraction below a round threshold is "hugging" it. */
    public static final double A4_HUG_FRACTION = 0.05;
    /** V1: actions-per-hour baseline above which burst velocity saturates. */
    public static final int V1_BURST_PER_HOUR_SATURATION = 6;
    /** C3: server-side raised-amount retry attempts at which retry pressure saturates. */
    public static final int C3_RETRY_SATURATION = 4;
    /** D1: device-age decay half-life in days (a fresh device is risky, decaying over ~30-60d). */
    public static final int D1_DEVICE_AGE_HALFLIFE_DAYS = 21;
    /** K2: limit-raise coverage saturates once the raised headroom covers this multiple of the amount. */
    public static final double K2_COVERAGE_SATURATION = 1.0;
    /** K3: beneficiary-add count at which the add-burst signal saturates. */
    public static final int K3_ADD_BURST_SATURATION = 3;
    /** K3: minimum beneficiary adds within the window for the burst to register at all. */
    public static final int K3_ADD_BURST_MIN = 2;
    /** MP1: new-counterparty share above which the fan-out tell saturates. */
    public static final double MP1_SHARE_SATURATION = 1.0;
    /** MP2: batch-total robust-z floor before the cadence/total anomaly registers. */
    public static final double MP2_Z_FLOOR = 2.0;
    /** MP4: batch-drain fraction floor (total/available) before it registers. */
    public static final double MP4_DRAIN_FLOOR = 0.5;
    /** MP4: batch-drain span over which the drain fraction saturates. */
    public static final double MP4_DRAIN_SPAN = 0.5;
    /** M2: k for the cosine k-NN over fraud exemplars. */
    public static final int M2_KNN_K = 25;

    // --- Typology tuning constants -----------------------------------------------------------
    /** Ty3: a "modest" first-time purchase-scam amount ceiling, in euro-cents (€2,000). */
    public static final long TY3_MODEST_AMOUNT_CENTS = 200_000L;
    /** Ty7: MP1 (new-counterparty share) threshold for mule fan-out. */
    public static final double TY7_MP1_THRESHOLD = 0.7;
    /** Ty7: MP4 (batch drain) threshold for mule fan-out. */
    public static final double TY7_MP4_THRESHOLD = 0.6;
}
