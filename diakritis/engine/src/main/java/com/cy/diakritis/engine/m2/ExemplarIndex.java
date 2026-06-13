package com.cy.diakritis.engine.m2;

/**
 * The §9.2 vector-similarity seam: a k-NN index over labelled fraud/legit exemplars that yields the
 * M2 "fraud-neighbor share" signal. One interface, two implementations chosen by config — the
 * Qdrant-backed index in production and the in-JVM Smile KDTree fallback at the venue — so M2 serves
 * the identical signal with zero infrastructure when Qdrant is absent.
 *
 * <p>The query vector is the standardized, L2-normalized 16-feature M1 vector (the same
 * {@code Features.java} vector M1 scores, standardized with {@code m2/m2_scaler.json}). The result is
 * the distance-weighted fraud share among the k nearest neighbours, in {@code [0,1]}.
 */
public interface ExemplarIndex {

    /**
     * Distance-weighted fraud share among the {@code k} nearest exemplars to the already
     * standardized + L2-normalized {@code queryVector}. Returns {@code 0.0} when the index is empty.
     */
    double fraudNeighborShare(double[] queryVector, int k);

    /** True when the index holds exemplars and can serve a non-trivial neighbour query. */
    boolean isAvailable();

    /** An empty index — {@link #fraudNeighborShare} is always 0 (the M2 resilience default). */
    static ExemplarIndex empty() {
        return new ExemplarIndex() {
            @Override
            public double fraudNeighborShare(double[] queryVector, int k) {
                return 0.0;
            }

            @Override
            public boolean isAvailable() {
                return false;
            }
        };
    }
}
