package com.cy.diakritis.engine.signal;

import com.cy.diakritis.engine.band.Weights;
import com.cy.diakritis.engine.store.GeoResolver;

import java.util.List;

/**
 * G2 — new network: the IP's /24 prefix is one the account has never used, even though the country is
 * familiar. It is the softer cousin of G1 (weight 6 vs 12) — a known country but a fresh access
 * network (new ISP, VPN, café) is mildly suspicious without the full unfamiliar-geo punch. The
 * prefix history lives under the {@code "NETWORK"} observation kind.
 *
 * <p>Fires (1.0) only when the country IS familiar (so it does not double-count with G1) and the /24
 * prefix is new to a non-empty network baseline. Cold start, unknown geo, or a familiar prefix all
 * score 0.
 */
public final class G2NewNetwork implements Signal {

    private static final String KIND_NETWORK = "NETWORK";

    @Override
    public String id() {
        return "G2";
    }

    @Override
    public double weight() {
        return Weights.G2;
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
        // Only a *familiar country* qualifies for G2; an unfamiliar country is G1's job.
        List<String> familiarCountries = ctx.obs().distinctValuesOfKind(ctx.accountId(), G1UnfamiliarGeo.KIND_GEO);
        if (!familiarCountries.contains(country)) {
            return 0.0;
        }
        String prefix = slash24(ip);
        if (prefix == null) {
            return 0.0;
        }
        if (!ctx.obs().hasAnyOfKind(ctx.accountId(), KIND_NETWORK)) {
            return 0.0;
        }
        List<String> familiarNetworks = ctx.obs().distinctValuesOfKind(ctx.accountId(), KIND_NETWORK);
        return familiarNetworks.contains(prefix) ? 0.0 : 1.0;
    }

    /** The /24 network prefix of a dotted-quad IPv4 (e.g. {@code 203.0.113.7 → 203.0.113}). */
    private static String slash24(String ip) {
        String[] octets = ip.trim().split("\\.");
        if (octets.length != 4) {
            return null;
        }
        return octets[0] + "." + octets[1] + "." + octets[2];
    }
}
