package com.cy.diakritis.engine.m2;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The §9.2 M2 standardizer: the {@code StandardScaler} fit on the M1 training fold, loaded from
 * {@code m2/m2_scaler.json} ({@code mean[]} and {@code scale[]} per feature column). The serve path
 * is {@code standardize -> L2-normalize -> cosine k-NN}; this class owns the first two steps so the
 * query vector and every exemplar share one transform.
 *
 * <p>The column order is bound to {@code m1/columns.txt} / {@code Features.COLUMNS}; the scaler JSON
 * carries the same 16 columns. A zero scale for any column is treated as 1.0 so a constant feature
 * standardizes to its mean-centred 0 rather than producing NaN/Inf.
 */
public final class M2Scaler {

    private final double[] mean;
    private final double[] scale;

    private M2Scaler(double[] mean, double[] scale) {
        this.mean = mean;
        this.scale = scale;
    }

    /** Number of feature columns this scaler standardizes. */
    public int length() {
        return mean.length;
    }

    /**
     * Load the scaler from {@code m2/m2_scaler.json} under {@code modelsDir}. Throws on a missing or
     * malformed file so the caller can decide to degrade M2 to the empty index.
     */
    public static M2Scaler load(Path modelsDir) throws IOException {
        Path path = modelsDir.resolve("m2").resolve("m2_scaler.json");
        byte[] bytes = Files.readAllBytes(path);
        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode root = mapper.readTree(bytes);
        double[] mean = readArray(root, "mean");
        double[] scale = readArray(root, "scale");
        if (mean.length == 0 || mean.length != scale.length) {
            throw new IOException("m2_scaler.json mean/scale arrays missing or mismatched");
        }
        return new M2Scaler(mean, scale);
    }

    /** Construct directly from arrays (test seam); a defensive copy is taken. */
    public static M2Scaler of(double[] mean, double[] scale) {
        if (mean == null || scale == null || mean.length != scale.length || mean.length == 0) {
            throw new IllegalArgumentException("mean/scale must be non-empty and equal length");
        }
        return new M2Scaler(mean.clone(), scale.clone());
    }

    /**
     * Standardize {@code raw} with {@code (x - mean) / scale} per column, then L2-normalize. The
     * returned vector is a unit vector so cosine similarity reduces to a dot product. A degenerate
     * all-zero standardized vector is returned as-is (its cosine distance to anything is undefined and
     * the index treats it as no evidence).
     */
    public double[] transform(double[] raw) {
        if (raw == null || raw.length != mean.length) {
            throw new IllegalArgumentException(
                    "expected " + mean.length + " features, got " + (raw == null ? 0 : raw.length));
        }
        double[] standardized = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            double s = scale[i] == 0.0 ? 1.0 : scale[i];
            standardized[i] = (raw[i] - mean[i]) / s;
        }
        return l2Normalize(standardized);
    }

    /** L2-normalize {@code v} in place-safe fashion; a zero vector is returned unchanged. */
    public static double[] l2Normalize(double[] v) {
        double sumSq = 0.0;
        for (double x : v) {
            sumSq += x * x;
        }
        double norm = Math.sqrt(sumSq);
        if (norm == 0.0 || Double.isNaN(norm)) {
            return v;
        }
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = v[i] / norm;
        }
        return out;
    }

    private static double[] readArray(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isArray()) {
            return new double[0];
        }
        double[] out = new double[node.size()];
        for (int i = 0; i < node.size(); i++) {
            out[i] = node.get(i).asDouble();
        }
        return out;
    }
}
