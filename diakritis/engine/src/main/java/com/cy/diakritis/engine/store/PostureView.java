package com.cy.diakritis.engine.store;

/**
 * Read-only projection of an account's rolling 72h risk posture (recent liquidation / limit /
 * beneficiary activity), read from the AccountPosture table. Drives the liquidation kill-chain
 * signals (K1) and related typologies.
 *
 * <p>Money is integer euro-cents; timestamps are epoch-millis. An absent posture is represented
 * by {@link #empty(long)} (all-zero counters anchored at {@code now}).
 */
public record PostureView(
        long fundsFreedEur72hCents,
        long limitRaised72hCents,
        long beneficiaryAddCount72h,
        long lastDepositBreakEpochMs
) {

    /** A posture with no recent activity; {@code lastDepositBreakEpochMs} anchored at {@code now}. */
    public static PostureView empty(long nowEpochMs) {
        return new PostureView(0L, 0L, 0L, nowEpochMs);
    }
}
