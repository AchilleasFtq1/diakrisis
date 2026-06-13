package com.cy.diakritis.engine.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A local, dependency-free {@link GeoResolver} backed by a table of IPv4 CIDR blocks mapped to ISO
 * country codes. Resolution is longest-prefix-match: the most specific block that contains the IP
 * wins, so overlapping ranges behave like a real routing table. An IP outside every block, a
 * malformed IP, or an empty table all resolve to {@link GeoResolver#UNKNOWN}.
 *
 * <p>This is the in-process fallback for the §5/§6 geo seam: it needs no network, no MaxMind file
 * and no container, which keeps the engine self-contained and the geo signals testable with
 * hand-built CIDR tables.
 */
public final class CidrGeoResolver implements GeoResolver {

    /** One CIDR block: a network address (as an unsigned 32-bit int) and a prefix length. */
    private record Block(int network, int prefixLen, String country) {
    }

    private final List<Block> blocks;

    private CidrGeoResolver(List<Block> blocks) {
        // Most-specific first so the first containing block is the longest-prefix match.
        blocks.sort((a, b) -> Integer.compare(b.prefixLen(), a.prefixLen()));
        this.blocks = List.copyOf(blocks);
    }

    /**
     * Build a resolver from a {@code CIDR -> country} map (e.g. {@code "203.0.113.0/24" -> "CY"}).
     * Malformed entries are skipped rather than throwing, so a partly-bad table still resolves the
     * blocks it can parse.
     */
    public static CidrGeoResolver of(Map<String, String> cidrToCountry) {
        List<Block> parsed = new ArrayList<>();
        if (cidrToCountry != null) {
            for (Map.Entry<String, String> e : cidrToCountry.entrySet()) {
                Block block = parseBlock(e.getKey(), e.getValue());
                if (block != null) {
                    parsed.add(block);
                }
            }
        }
        return new CidrGeoResolver(parsed);
    }

    /** A resolver over an empty table; every IP is {@link GeoResolver#UNKNOWN}. */
    public static CidrGeoResolver empty() {
        return new CidrGeoResolver(new ArrayList<>());
    }

    @Override
    public String country(String ip) {
        Integer addr = parseIpv4(ip);
        if (addr == null) {
            return UNKNOWN;
        }
        int address = addr;
        for (Block block : blocks) {
            int mask = maskFor(block.prefixLen());
            if ((address & mask) == (block.network() & mask)) {
                return block.country();
            }
        }
        return UNKNOWN;
    }

    private static Block parseBlock(String cidr, String country) {
        if (cidr == null || country == null || country.isBlank()) {
            return null;
        }
        String[] parts = cidr.trim().split("/");
        if (parts.length != 2) {
            return null;
        }
        Integer network = parseIpv4(parts[0]);
        if (network == null) {
            return null;
        }
        int prefixLen;
        try {
            prefixLen = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException ex) {
            return null;
        }
        if (prefixLen < 0 || prefixLen > 32) {
            return null;
        }
        return new Block(network, prefixLen, country.trim());
    }

    /** Parse a dotted-quad IPv4 string into an unsigned 32-bit int, or null when malformed. */
    private static Integer parseIpv4(String ip) {
        if (ip == null) {
            return null;
        }
        String[] octets = ip.trim().split("\\.");
        if (octets.length != 4) {
            return null;
        }
        int value = 0;
        for (String octet : octets) {
            int o;
            try {
                o = Integer.parseInt(octet);
            } catch (NumberFormatException ex) {
                return null;
            }
            if (o < 0 || o > 255) {
                return null;
            }
            value = (value << 8) | o;
        }
        return value;
    }

    private static int maskFor(int prefixLen) {
        // A /0 must mask everything off (shifting an int by 32 is a no-op in Java, hence the guard).
        return prefixLen == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefixLen));
    }
}
