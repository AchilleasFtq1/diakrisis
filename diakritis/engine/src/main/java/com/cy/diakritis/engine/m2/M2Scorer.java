package com.cy.diakritis.engine.m2;

import com.cy.diakritis.engine.band.Weights;
import com.cy.diakritis.engine.m1.Features;
import com.cy.diakritis.engine.signal.SignalContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The §9.2 M2 serve path: turn a {@link SignalContext} into the distance-weighted fraud-neighbor
 * share in {@code [0,1]}. It owns the full pipeline — build the raw 16-feature {@link Features}
 * vector, standardize + L2-normalize it with {@link M2Scaler} (from {@code m2/m2_scaler.json}), then
 * cosine k-NN (k={@value Weights#M2_KNN_K}) over the in-JVM {@link KdTreeExemplarIndex}.
 *
 * <p><b>Resilience (contract):</b> the model is never trained here, only loaded. If the scaler or the
 * exemplar table is unavailable, or any scoring step throws, M2 scores {@code 0.0} so the rest of the
 * engine is unaffected — M2 is an additive, capped signal, never a hard dependency.
 *
 * <p>Exemplar table format: {@code m2/exemplars.csv}, a header row then one row per exemplar of
 * {@code label,f0,f1,...,f15} where {@code label} is {@code 1} (fraud) or {@code 0} (legit) and the
 * features are the raw {@link Features#COLUMNS} values (the same vector M1 scores). They are
 * standardized + L2-normalized at load with the very scaler the query uses, so query and exemplars
 * share one transform.
 */
public final class M2Scorer {

    private static final Logger log = LoggerFactory.getLogger(M2Scorer.class);
    private static final String EXEMPLARS_FILE = "exemplars.csv";

    private final M2Scaler scaler;
    private final ExemplarIndex index;
    private final boolean loaded;

    private M2Scorer(M2Scaler scaler, ExemplarIndex index, boolean loaded) {
        this.scaler = scaler;
        this.index = index;
        this.loaded = loaded;
    }

    /**
     * Load the M2 scorer from a models directory. Best-effort: a missing scaler or exemplar table, or
     * any load error, yields a constant-0 scorer (never throws). When the scaler loads but the
     * exemplar table is absent, M2 also degrades to 0 — exactly the "exemplars unavailable" branch the
     * contract specifies.
     */
    public static M2Scorer load(Path modelsDir) {
        M2Scaler loadedScaler = null;
        ExemplarIndex loadedIndex = ExemplarIndex.empty();
        boolean ok = false;
        try {
            loadedScaler = M2Scaler.load(modelsDir);
            loadedIndex = loadKdTreeExemplars(modelsDir.resolve("m2").resolve(EXEMPLARS_FILE), loadedScaler);
            ok = loadedIndex.isAvailable();
            if (ok) {
                log.info("M2 exemplar index loaded from {} ({} dims)", modelsDir, loadedScaler.length());
            } else {
                log.info("M2 scaler loaded but no exemplar table present; M2 signal will score 0.");
            }
        } catch (Exception | LinkageError e) {
            log.warn("M2 unavailable; M2 signal will score 0. cause={}", e.toString());
        }
        return new M2Scorer(loadedScaler, loadedIndex, ok);
    }

    /** Build directly from a scaler and an index (test seam). */
    public static M2Scorer of(M2Scaler scaler, ExemplarIndex index) {
        boolean ok = scaler != null && index != null && index.isAvailable();
        return new M2Scorer(scaler, index == null ? ExemplarIndex.empty() : index, ok);
    }

    /** True when scaler and a non-empty exemplar index are loaded and M2 is scoring live. */
    public boolean isLoaded() {
        return loaded;
    }

    /**
     * Distance-weighted fraud-neighbor share in {@code [0,1]} for the event in {@code ctx}. Returns
     * 0.0 when M2 is unavailable or anything throws.
     */
    public double score(SignalContext ctx) {
        if (!loaded || scaler == null) {
            return 0.0;
        }
        try {
            double[] raw = Features.toVector(ctx);
            double[] query = scaler.transform(raw);
            if (isZeroVector(query)) {
                // Degenerate query (every raw feature exactly equals its column mean): the standardized
                // vector is all-zero and carries no evidence. Abstain (0.0) as the contract documents —
                // running the k-NN over a zero vector would otherwise collapse to the exemplars' base
                // fraud rate, silently injecting the population prior as an M2 signal.
                return 0.0;
            }
            double share = index.fraudNeighborShare(query, Weights.M2_KNN_K);
            if (Double.isNaN(share)) {
                return 0.0;
            }
            return Math.max(0.0, Math.min(1.0, share));
        } catch (Exception | LinkageError e) {
            log.warn("M2 scoring failed; returning 0. cause={}", e.toString());
            return 0.0;
        }
    }

    /** True when every component is exactly zero — a degenerate, no-evidence query vector. */
    private static boolean isZeroVector(double[] v) {
        for (double x : v) {
            if (x != 0.0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Load and transform the in-JVM KDTree exemplar table (the §9.2 fallback backend). Each data row is
     * {@code label,f0..f15}; the features are standardized + L2-normalized with {@code scaler}. A
     * missing file or zero usable rows yields the empty index. Exposed so the M2 backend factory can
     * build the KDTree fallback with the very same loader the scorer uses.
     */
    public static ExemplarIndex loadKdTreeExemplars(Path path, M2Scaler scaler) throws IOException {
        if (!Files.exists(path)) {
            return ExemplarIndex.empty();
        }
        List<double[]> vectors = new ArrayList<>();
        List<Integer> labels = new ArrayList<>();
        int expectedFeatureCols = scaler.length();

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
            if (parts.length != expectedFeatureCols + 1) {
                continue; // skip malformed rows rather than failing the whole load
            }
            int label = parseLabel(parts[0]);
            if (label < 0) {
                continue;
            }
            double[] raw = new double[expectedFeatureCols];
            boolean rowOk = true;
            for (int i = 0; i < expectedFeatureCols; i++) {
                try {
                    raw[i] = Double.parseDouble(parts[i + 1].trim());
                } catch (NumberFormatException ex) {
                    rowOk = false;
                    break;
                }
            }
            if (!rowOk) {
                continue;
            }
            vectors.add(scaler.transform(raw));
            labels.add(label);
        }

        if (vectors.isEmpty()) {
            return ExemplarIndex.empty();
        }
        double[][] matrix = vectors.toArray(new double[0][]);
        int[] labelArray = new int[labels.size()];
        for (int i = 0; i < labelArray.length; i++) {
            labelArray[i] = labels.get(i);
        }
        return new KdTreeExemplarIndex(matrix, labelArray);
    }

    private static int parseLabel(String raw) {
        String token = raw == null ? "" : raw.trim();
        return switch (token) {
            case "1" -> 1;
            case "0" -> 0;
            default -> -1;
        };
    }
}
