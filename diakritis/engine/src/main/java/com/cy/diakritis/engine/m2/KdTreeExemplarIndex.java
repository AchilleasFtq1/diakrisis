package com.cy.diakritis.engine.m2;

import smile.neighbor.KDTree;
import smile.neighbor.Neighbor;

/**
 * In-JVM {@link ExemplarIndex} over labelled fraud/legit exemplars, backed by Smile's
 * {@link KDTree} (§9.2 fallback — no Qdrant container required). Exemplar vectors are the
 * standardized, L2-normalized 16-feature M1 vectors; on the unit sphere cosine distance is
 * {@code 1 - dot}, so a Euclidean k-NN over unit vectors ranks neighbours identically to a cosine
 * k-NN. The signal is the distance-weighted fraud share among the k nearest neighbours:
 * {@code Σ(w·isFraud)/Σw} with {@code w = 1 / (cosineDistance + 1e-6)}.
 *
 * <p>The Euclidean distance Smile reports between two unit vectors {@code a} and {@code b} is
 * {@code sqrt(2 - 2·dot)}; the cosine distance {@code 1 - dot} is recovered as {@code d²/2}, so we
 * never re-fetch the neighbour vectors.
 */
public final class KdTreeExemplarIndex implements ExemplarIndex {

    private static final double DISTANCE_EPS = 1e-6;

    private final KDTree<Integer> tree;
    private final int[] labels;
    private final int size;

    /**
     * Build the index from already standardized + L2-normalized exemplar vectors and their labels
     * ({@code 1} = fraud, {@code 0} = legit). The arrays must be parallel and non-empty; the caller
     * (the factory) is responsible for degrading to {@link ExemplarIndex#empty()} otherwise.
     */
    public KdTreeExemplarIndex(double[][] unitVectors, int[] labels) {
        if (unitVectors == null || labels == null || unitVectors.length != labels.length
                || unitVectors.length == 0) {
            throw new IllegalArgumentException("vectors/labels must be parallel and non-empty");
        }
        Integer[] indices = new Integer[unitVectors.length];
        for (int i = 0; i < unitVectors.length; i++) {
            indices[i] = i;
        }
        this.tree = new KDTree<>(unitVectors, indices);
        this.labels = labels.clone();
        this.size = unitVectors.length;
    }

    @Override
    public boolean isAvailable() {
        return size > 0;
    }

    @Override
    public double fraudNeighborShare(double[] queryVector, int k) {
        if (size == 0 || queryVector == null) {
            return 0.0;
        }
        int kEffective = Math.min(k, size);
        if (kEffective <= 0) {
            return 0.0;
        }

        Neighbor<double[], Integer>[] neighbors = tree.search(queryVector, kEffective);
        double weightedFraud = 0.0;
        double weightSum = 0.0;
        for (Neighbor<double[], Integer> neighbor : neighbors) {
            // Smile returns Euclidean distance between unit vectors: dEuclid = sqrt(2 - 2·dot).
            // Cosine distance (1 - dot) is therefore dEuclid² / 2, clamped to [0,2] for safety.
            double cosineDistance = Math.max(0.0, (neighbor.distance * neighbor.distance) / 2.0);
            double weight = 1.0 / (cosineDistance + DISTANCE_EPS);
            int label = labels[neighbor.value];
            weightedFraud += weight * label;
            weightSum += weight;
        }
        if (weightSum == 0.0) {
            return 0.0;
        }
        double share = weightedFraud / weightSum;
        if (Double.isNaN(share)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, share));
    }
}
