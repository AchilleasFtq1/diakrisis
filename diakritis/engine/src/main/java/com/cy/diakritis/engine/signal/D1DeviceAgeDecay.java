package com.cy.diakritis.engine.signal;

import com.cy.diakritis.engine.band.Weights;

import java.util.Optional;

/**
 * D1 — device-age decay: a freshly-seen device is risky, that risk decaying as the device proves
 * itself over weeks. The device's first sighting comes from the observation store ({@code "DEVICE"}
 * kind); the signal decays as {@code exp(-ageDays·ln2 / halfLife)} with a half-life of
 * {@link Weights#D1_DEVICE_AGE_HALFLIFE_DAYS} days, so a brand-new device scores ~1.0 and a
 * device a couple of months old scores ~0 (the §6 "≈0 after 30-60d" warm-up ramp).
 *
 * <p><b>Cold-start discipline.</b> D1 only speaks when the account has an established device baseline.
 * With no observed device history at all (a cold-start account, or no observation store wired) there
 * is no "device age" to reason about and the engine must not invent device risk — so D1 is 0. That is
 * what keeps an unseen-but-uninstrumented device from spuriously corroborating the safe-account
 * typology. When the account HAS device history but this specific device is new to it, the device is
 * genuinely fresh and scores the full decayed risk.
 */
public final class D1DeviceAgeDecay implements Signal {

    private static final String KIND_DEVICE = "DEVICE";
    private static final double LN2 = Math.log(2.0);

    @Override
    public String id() {
        return "D1";
    }

    @Override
    public double weight() {
        return Weights.D1;
    }

    @Override
    public double value(SignalContext ctx) {
        String deviceId = deviceId(ctx);
        if (deviceId == null) {
            return 0.0;
        }
        // No device baseline for this account → no device age to reason about (cold start) → 0.
        if (!ctx.obs().hasAnyOfKind(ctx.accountId(), KIND_DEVICE)) {
            return 0.0;
        }
        Optional<Long> firstSeen = ctx.obs().firstSeenEpochMs(ctx.accountId(), KIND_DEVICE, deviceId);
        if (firstSeen.isEmpty()) {
            // The account has device history, but this specific device is new to it → full freshness.
            return 1.0;
        }
        double ageDays = SignalMath.ageDays(firstSeen.get(), ctx.now().toEpochMilli());
        double halfLife = Weights.D1_DEVICE_AGE_HALFLIFE_DAYS;
        if (halfLife <= 0) {
            return 0.0;
        }
        double decay = Math.exp(-ageDays * LN2 / halfLife);
        return SignalMath.clamp01(decay);
    }

    private static String deviceId(SignalContext ctx) {
        if (ctx.event().context() == null || ctx.event().context().device() == null) {
            return null;
        }
        return ctx.event().context().device().deviceId();
    }
}
