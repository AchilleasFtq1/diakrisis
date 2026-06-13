package com.cy.diakritis.engine.store;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies the local CIDR-table {@link GeoResolver}: longest-prefix match, miss → unknown, bad IP. */
class CidrGeoResolverTest {

    @Test
    void resolvesByLongestPrefix() {
        // A broad /8 mapped to CY, a more specific /24 inside it mapped to RU → the /24 wins.
        GeoResolver geo = CidrGeoResolver.of(Map.of(
                "203.0.0.0/8", "CY",
                "203.0.113.0/24", "RU"));
        assertEquals("RU", geo.country("203.0.113.7"), "most specific block wins");
        assertEquals("CY", geo.country("203.0.200.7"), "falls back to the broader block");
    }

    @Test
    void unknownForMissAndForBadInput() {
        GeoResolver geo = CidrGeoResolver.of(Map.of("10.0.0.0/8", "CY"));
        assertEquals(GeoResolver.UNKNOWN, geo.country("198.51.100.1"), "outside every block → unknown");
        assertEquals(GeoResolver.UNKNOWN, geo.country("not-an-ip"), "malformed IP → unknown");
        assertEquals(GeoResolver.UNKNOWN, geo.country(null), "null IP → unknown");
    }

    @Test
    void emptyTableIsAlwaysUnknown() {
        assertEquals(GeoResolver.UNKNOWN, CidrGeoResolver.empty().country("203.0.113.7"));
        assertEquals(GeoResolver.UNKNOWN, GeoResolver.unknownAll().country("203.0.113.7"));
    }

    @Test
    void slashZeroMatchesEverything() {
        GeoResolver geo = CidrGeoResolver.of(Map.of("0.0.0.0/0", "XX"));
        assertEquals("XX", geo.country("8.8.8.8"));
        assertEquals("XX", geo.country("203.0.113.7"));
    }
}
