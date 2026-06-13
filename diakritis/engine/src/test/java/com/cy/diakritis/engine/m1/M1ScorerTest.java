package com.cy.diakritis.engine.m1;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads the real pre-trained M1 model from the diakrisis-models directory and asserts the scorer
 * returns a value in {@code [0,1]} for hand-built feature vectors, and that it degrades to 0 when
 * the model is unavailable.
 */
class M1ScorerTest {

    private static final Path MODELS_DIR =
            Path.of("/Users/achilleaseftychiou/Documents/Projects/diakrisis/diakrisis-models");

    static boolean modelsPresent() {
        return Files.isReadable(MODELS_DIR.resolve("m1/m1.model"))
                && Files.isReadable(MODELS_DIR.resolve("m1/isotonic.csv"))
                && Files.isReadable(MODELS_DIR.resolve("m1/percentiles.csv"));
    }

    @Test
    @EnabledIf("modelsPresent")
    void loadsRealModelAndScoresInUnitInterval() {
        M1Scorer scorer = new M1Scorer(MODELS_DIR);
        assertTrue(scorer.isLoaded(), "real m1.model must load from the models directory");

        // A benign, low-amount vector and an anomalous high-amount vector both must land in [0,1].
        double[] benign = zeros();
        benign[0] = Math.log1p(120.0); // amt_log for €120
        benign[13] = 1.0;              // amt_ratio first-occurrence
        double benignScore = scorer.scoreVector(benign);
        assertInUnitInterval(benignScore);

        double[] anomalous = zeros();
        anomalous[0] = Math.log1p(4850.0); // amt_log for €4850
        anomalous[13] = 50.0;              // amt_ratio capped high
        anomalous[14] = 1.0;               // email_missing
        double anomalousScore = scorer.scoreVector(anomalous);
        assertInUnitInterval(anomalousScore);
    }

    @Test
    @EnabledIf("modelsPresent")
    void distinctVectorsCanProduceDistinctScores() {
        M1Scorer scorer = new M1Scorer(MODELS_DIR);

        double[] low = zeros();
        low[0] = Math.log1p(50.0);
        low[13] = 1.0;

        double[] high = zeros();
        high[0] = Math.log1p(9999.0);
        high[8] = 4.0; // d_miss_count = all four recency deltas missing
        high[9] = -1.0;
        high[10] = -1.0;
        high[11] = -1.0;
        high[12] = -1.0;
        high[13] = 50.0;
        high[14] = 1.0;

        double lowScore = scorer.scoreVector(low);
        double highScore = scorer.scoreVector(high);
        assertInUnitInterval(lowScore);
        assertInUnitInterval(highScore);
        // The model is a real classifier — different inputs should generally not collapse to the
        // same calibrated percentile; assert they differ to prove the model is actually consulted.
        assertNotEquals(lowScore, highScore, 1e-12,
                "distinct feature vectors must drive distinct calibrated scores");
    }

    @Test
    void degradesToZeroWhenModelDirectoryMissing() {
        M1Scorer scorer = new M1Scorer(Path.of("/nonexistent/models/dir"));
        assertEquals(false, scorer.isLoaded(), "missing directory must not load a model");
        assertEquals(0.0, scorer.scoreVector(zeros()), 0.0,
                "an unavailable model must score 0, never throw");
    }

    private static double[] zeros() {
        return new double[Features.FEATURE_COUNT];
    }

    private static void assertInUnitInterval(double v) {
        assertTrue(v >= 0.0 && v <= 1.0, "score must be in [0,1] but was " + v);
    }
}
