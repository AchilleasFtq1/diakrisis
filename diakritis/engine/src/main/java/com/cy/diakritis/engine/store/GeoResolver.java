package com.cy.diakritis.engine.store;

/**
 * Resolves an IP address to an ISO country code. This is the §5/§6 seam between the engine's geo
 * signals (G1 unfamiliar-geo, G2 new-network) and whatever geolocation source a deployment plugs in.
 *
 * <p>Implementations must be total and never throw: an unroutable, malformed or unknown IP resolves
 * to {@link #UNKNOWN}. The engine treats {@code "unknown"} as "no geo evidence", so a missing
 * resolver degrades the geo signals to zero rather than taking the decision path down.
 */
public interface GeoResolver {

    /** Sentinel country for an IP that cannot be resolved. */
    String UNKNOWN = "unknown";

    /** ISO country code for {@code ip}, or {@link #UNKNOWN} when it cannot be resolved. Never null. */
    String country(String ip);

    /** A resolver that knows nothing — every IP is {@link #UNKNOWN} (the resilient default). */
    static GeoResolver unknownAll() {
        return ip -> UNKNOWN;
    }
}
