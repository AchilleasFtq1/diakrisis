package com.cy.diakritis.engine.m1;

import com.cy.diakritis.engine.signal.SignalContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smile.classification.GradientTreeBoost;
import smile.data.Tuple;
import smile.data.type.StructType;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the pre-trained M1 GradientTreeBoost model plus its isotonic calibration and percentile
 * map, and turns a {@link SignalContext} into a calibrated, percentile-ranked fraud score in
 * {@code [0,1]}.
 *
 * <p>Pipeline: build the 16-feature vector ({@link Features}) → model posteriori[1] (raw fraud
 * probability) → isotonic recalibration → percentile-rank against the validation distribution.
 *
 * <p><b>Resilience:</b> the model is never trained here, only loaded. If any artifact fails to load
 * or any prediction throws, the scorer degrades to a constant 0.0 so the rest of the engine keeps
 * working — M1 is an additive signal, never a hard dependency. {@link #isLoaded()} reports whether
 * the live model is in play.
 */
public final class M1Scorer {

    private static final Logger log = LoggerFactory.getLogger(M1Scorer.class);

    private static final String MODEL_FILE = "m1/m1.model";
    private static final String ISOTONIC_FILE = "m1/isotonic.csv";
    private static final String PERCENTILES_FILE = "m1/percentiles.csv";

    private final GradientTreeBoost model;
    private final StructType schema;
    private final StepFunction isotonic;
    private final PercentileRanker percentiles;
    private final boolean loaded;

    /**
     * Construct from a models directory (e.g. {@code .../diakrisis-models}). Loading is best-effort:
     * a failure is logged and the scorer becomes a constant-0 stub at the API boundary (never throws).
     */
    public M1Scorer(Path modelsDir) {
        GradientTreeBoost loadedModel = null;
        StructType loadedSchema = null;
        StepFunction loadedIsotonic = null;
        PercentileRanker loadedPercentiles = null;
        boolean ok = false;
        try {
            loadedModel = loadModel(modelsDir.resolve(MODEL_FILE));
            loadedSchema = loadedModel.schema();
            loadedIsotonic = loadIsotonic(modelsDir.resolve(ISOTONIC_FILE));
            loadedPercentiles = loadPercentiles(modelsDir.resolve(PERCENTILES_FILE));
            ok = true;
            log.info("M1 model loaded from {} (schema {} features)", modelsDir, loadedSchema.length());
        } catch (Exception | LinkageError e) {
            // LinkageError caught too: a missing smile transitive class must not take the engine down.
            log.warn("M1 model unavailable; M1 signal will score 0. cause={}", e.toString());
        }
        this.model = loadedModel;
        this.schema = loadedSchema;
        this.isotonic = loadedIsotonic;
        this.percentiles = loadedPercentiles;
        this.loaded = ok;
    }

    /** True when the live model and calibration artifacts are loaded and scoring is active. */
    public boolean isLoaded() {
        return loaded;
    }

    /**
     * Calibrated, percentile-ranked fraud score in {@code [0,1]} for the event in {@code ctx}.
     * Returns 0.0 if the model is unavailable or anything throws during scoring.
     */
    public double score(SignalContext ctx) {
        if (!loaded) {
            return 0.0;
        }
        try {
            double[] vector = Features.toVector(ctx);
            return scoreVector(vector);
        } catch (Exception | LinkageError e) {
            log.warn("M1 scoring failed; returning 0. cause={}", e.toString());
            return 0.0;
        }
    }

    /**
     * Score a raw feature vector (in {@link Features#COLUMNS} order) directly. Exposed for tests so
     * a hand-built vector can be asserted to land in {@code [0,1]}.
     */
    public double scoreVector(double[] vector) {
        if (!loaded) {
            return 0.0;
        }
        if (vector.length != Features.FEATURE_COUNT) {
            throw new IllegalArgumentException("expected " + Features.FEATURE_COUNT + " features, got " + vector.length);
        }
        Tuple tuple = Tuple.of(vector, schema);
        double[] posteriori = new double[2];
        model.predict(tuple, posteriori);
        double rawProb = posteriori[1];
        double calibrated = isotonic.apply(rawProb);
        double ranked = percentiles.rank(calibrated);
        return clamp01(ranked);
    }

    private static GradientTreeBoost loadModel(Path modelPath) throws IOException, ClassNotFoundException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(modelPath));
             ObjectInputStream ois = new ObjectInputStream(in)) {
            Object obj = ois.readObject();
            if (!(obj instanceof GradientTreeBoost gbt)) {
                throw new IOException("m1.model is not a GradientTreeBoost: " + obj.getClass().getName());
            }
            return gbt;
        }
    }

    private static StepFunction loadIsotonic(Path path) throws IOException {
        List<double[]> rows = readNumericCsv(path);
        double[] thresholds = new double[rows.size()];
        double[] values = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            thresholds[i] = rows.get(i)[0];
            values[i] = rows.get(i)[1];
        }
        return new StepFunction(thresholds, values);
    }

    private static PercentileRanker loadPercentiles(Path path) throws IOException {
        List<double[]> rows = readNumericCsv(path);
        double[] percentile = new double[rows.size()];
        double[] score = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            percentile[i] = rows.get(i)[0];
            score[i] = rows.get(i)[1];
        }
        return new PercentileRanker(percentile, score);
    }

    /**
     * Read a two-column numeric CSV with a header row, skipping blank lines. Kept tiny and
     * dependency-free (no commons-csv on the engine classpath).
     */
    private static List<double[]> readNumericCsv(Path path) throws IOException {
        List<double[]> rows = new ArrayList<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        boolean header = true;
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            if (header) {
                header = false; // first non-blank line is the column header
                continue;
            }
            String[] parts = line.split(",");
            if (parts.length < 2) {
                continue;
            }
            rows.add(new double[]{Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())});
        }
        if (rows.isEmpty()) {
            throw new IOException("no numeric rows parsed from " + path);
        }
        return rows;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        if (v < 0.0) {
            return 0.0;
        }
        return Math.min(v, 1.0);
    }
}
