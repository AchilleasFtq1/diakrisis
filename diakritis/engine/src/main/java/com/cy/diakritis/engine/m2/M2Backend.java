package com.cy.diakritis.engine.m2;

/**
 * Selects which {@link ExemplarIndex} implementation backs the §9.2 M2 signal.
 *
 * <ul>
 *   <li>{@link #QDRANT} — the production path: live cosine k-NN over a running Qdrant
 *       {@code fraud_exemplars} collection (gRPC :6334). When Qdrant is unreachable at boot the
 *       factory falls back to {@link #KDTREE} so M2 still serves a signal.</li>
 *   <li>{@link #KDTREE} — the in-JVM Smile KDTree over the {@code m2/exemplars.csv} table; the
 *       resilience fallback that needs no container.</li>
 *   <li>{@link #AUTO} — prefer Qdrant when reachable, else KDTree (the default).</li>
 * </ul>
 */
public enum M2Backend {
    QDRANT,
    KDTREE,
    AUTO;

    /** Parse the {@code diakrisis.m2.backend} config token; unknown/blank → {@link #AUTO}. */
    public static M2Backend fromConfig(String token) {
        if (token == null) {
            return AUTO;
        }
        return switch (token.trim().toLowerCase()) {
            case "qdrant" -> QDRANT;
            case "kdtree" -> KDTREE;
            default -> AUTO;
        };
    }
}
