package com.cy.diakritis.engine.signal;

import com.cy.diakritis.common.dto.Platform;
import com.cy.diakritis.engine.band.Weights;

import java.util.List;

/**
 * D2 — platform anomaly: an account that has only ever used one platform (e.g. Android-only) suddenly
 * appears on another (WEB). A platform switch is a weak-but-real account-takeover tell. The platform
 * history is the distinct {@code "PLATFORM"} observations; the current platform comes from the
 * session's device.
 *
 * <p>Fires (1.0) only when the account has a non-empty platform baseline that does NOT already include
 * the current platform. Cold start (no baseline) scores 0 — there is no established platform to break.
 */
public final class D2PlatformAnomaly implements Signal {

    private static final String KIND_PLATFORM = "PLATFORM";

    @Override
    public String id() {
        return "D2";
    }

    @Override
    public double weight() {
        return Weights.D2;
    }

    @Override
    public double value(SignalContext ctx) {
        Platform platform = platform(ctx);
        if (platform == null) {
            return 0.0;
        }
        if (!ctx.obs().hasAnyOfKind(ctx.accountId(), KIND_PLATFORM)) {
            return 0.0;
        }
        List<String> seen = ctx.obs().distinctValuesOfKind(ctx.accountId(), KIND_PLATFORM);
        return seen.contains(platform.name()) ? 0.0 : 1.0;
    }

    private static Platform platform(SignalContext ctx) {
        if (ctx.event().context() == null || ctx.event().context().device() == null) {
            return null;
        }
        return ctx.event().context().device().platform();
    }
}
