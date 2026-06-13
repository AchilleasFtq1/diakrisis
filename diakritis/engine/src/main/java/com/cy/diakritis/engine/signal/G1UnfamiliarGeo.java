package com.cy.diakritis.engine.signal;

import com.cy.diakritis.engine.band.Weights;
import com.cy.diakritis.engine.store.GeoResolver;

import java.util.List;

/**
 * G1 — unfamiliar geography: the IP resolves to a country this account has never transacted from. A
 * sudden payment originating in a new country is a strong account-takeover / coached-from-abroad
 * tell. The IP→country lookup is the {@link GeoResolver} seam; the account's familiar countries are
 * the distinct {@code "GEO"} observations.
 *
 * <p>Resilient and honest: an unresolvable IP ({@link GeoResolver#UNKNOWN}) or a cold-start account
 * with no geo history both score 0 — there is no evidence of an unfamiliar country, so the signal
 * stays silent rather than guessing.
 */
public final class G1UnfamiliarGeo implements Signal {

    static final String KIND_GEO = "GEO";

    @Override
    public String id() {
        return "G1";
    }

    @Override
    public double weight() {
        return Weights.G1;
    }

    @Override
    public double value(SignalContext ctx) {
        String ip = ctx.event().context() == null ? null : ctx.event().context().ip();
        if (ip == null) {
            return 0.0;
        }
        String country = ctx.geo().country(ip);
        if (country == null || GeoResolver.UNKNOWN.equals(country)) {
            return 0.0;
        }
        if (!ctx.obs().hasAnyOfKind(ctx.accountId(), KIND_GEO)) {
            // Cold start: no familiar-country baseline yet → no evidence of "unfamiliar".
            return 0.0;
        }
        List<String> familiar = ctx.obs().distinctValuesOfKind(ctx.accountId(), KIND_GEO);
        return familiar.contains(country) ? 0.0 : 1.0;
    }
}
