package com.cy.diakritis.engine.m2;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit fixtures for the §9.2 M2 vector-similarity path: the scaler transform, the in-JVM Smile
 * KDTree exemplar index (distance-weighted fraud share), and the resilient empty-index default.
 */
class M2ExemplarTest {

    private static final Path MODELS_DIR =
            Path.of("/Users/achilleaseftychiou/Documents/Projects/diakrisis/diakrisis-models");

    @Test
    void scalerStandardizesAndL2Normalizes() {
        // Two columns: mean {0,0}, scale {1,2}. Raw {2,4} → standardized {2,2} → unit vector {.707,.707}.
        M2Scaler scaler = M2Scaler.of(new double[]{0.0, 0.0}, new double[]{1.0, 2.0});
        double[] unit = scaler.transform(new double[]{2.0, 4.0});
        double norm = Math.sqrt(unit[0] * unit[0] + unit[1] * unit[1]);
        assertEquals(1.0, norm, 1e-9, "transform must L2-normalize to a unit vector");
        assertEquals(unit[0], unit[1], 1e-9, "equal standardized components stay equal after L2");
    }

    @Test
    void kdTreeFraudShareIsDistanceWeighted() {
        // Two unit exemplars: one fraud near the query, one legit far from it.
        double[] fraud = M2Scaler.l2Normalize(new double[]{1.0, 0.1});
        double[] legit = M2Scaler.l2Normalize(new double[]{-1.0, 0.1});
        KdTreeExemplarIndex index = new KdTreeExemplarIndex(
                new double[][]{fraud, legit}, new int[]{1, 0});
        assertTrue(index.isAvailable());

        double[] query = M2Scaler.l2Normalize(new double[]{1.0, 0.0});
        double share = index.fraudNeighborShare(query, 2);
        assertTrue(share > 0.5, "the nearer fraud exemplar must dominate the weighted share: " + share);
        assertTrue(share <= 1.0, "share is a fraction");
    }

    @Test
    void kdTreeAllFraudGivesShareOne() {
        double[] a = M2Scaler.l2Normalize(new double[]{1.0, 0.0});
        double[] b = M2Scaler.l2Normalize(new double[]{0.9, 0.1});
        KdTreeExemplarIndex index = new KdTreeExemplarIndex(new double[][]{a, b}, new int[]{1, 1});
        double share = index.fraudNeighborShare(M2Scaler.l2Normalize(new double[]{1.0, 0.05}), 2);
        assertEquals(1.0, share, 1e-9, "all-fraud neighbourhood → share 1.0");
    }

    @Test
    void emptyIndexScoresZero() {
        assertFalse(ExemplarIndex.empty().isAvailable());
        assertEquals(0.0, ExemplarIndex.empty().fraudNeighborShare(new double[]{1, 2, 3}, 5), 1e-9);
    }

    @Test
    void scorerDegradesToZeroWhenExemplarsAbsent() {
        // The real models dir ships m2_scaler.json but no exemplars.csv → M2 must score 0 (resilience).
        M2Scorer scorer = M2Scorer.load(MODELS_DIR);
        assertFalse(scorer.isLoaded(), "M2 must be unloaded when no exemplar table is present");
    }

    @Test
    void scorerDegradesToZeroForMissingModelsDir() {
        M2Scorer scorer = M2Scorer.load(Path.of("/nonexistent/models/dir"));
        assertFalse(scorer.isLoaded(), "M2 must be unloaded for a missing models dir");
    }
}
