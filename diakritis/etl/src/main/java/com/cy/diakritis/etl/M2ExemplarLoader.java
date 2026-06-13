package com.cy.diakritis.etl;

import com.cy.diakritis.engine.m2.M2Scaler;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The §9.2 M2 exemplar loader: builds the labelled fraud/legit exemplar vectors from the IEEE-CIS
 * {@code train_transaction.csv} (the very rows {@code m1.model} was trained on, time-sorted by
 * {@code TransactionDT}), derives the 16 raw features per {@code FEATURE_SPEC.md}, standardizes +
 * L2-normalizes them with the production {@link M2Scaler} ({@code m2/m2_scaler.json}), and UPSERTS
 * them into the Qdrant {@code fraud_exemplars} collection (cosine, payload {@code {fraud:0|1}}) over
 * the Java gRPC client (:6334).
 *
 * <p>It also writes the identical (raw) exemplar table to {@code <models-dir>/m2/exemplars.csv} so the
 * in-JVM Smile KDTree fallback indexes the SAME exemplar set — the two backends therefore return the
 * same distance-weighted fraud share on the same query vector (the parity the contract demands).
 *
 * <p>Bounding: the IEEE-CIS train split holds ~590k rows; the loader samples a bounded, stratified
 * exemplar set ({@code --max-exemplars}, default 45,000) by keeping every fraud row plus a 1-in-N
 * sample of legit rows, while computing the {@code amt_ratio} expanding mean over EVERY prior row of
 * each {@code card1} (not just the sampled ones) so the sampled features are exact.
 *
 * <p>Args: {@code --csv <train_transaction.csv>} {@code --models-dir <dir>} {@code --qdrant-host h}
 * {@code --qdrant-port 6334} {@code --collection fraud_exemplars} {@code --max-exemplars 45000}
 * {@code --recreate} (drop+recreate the collection first). The model is never trained — only the
 * pre-fit scaler is loaded.
 */
public final class M2ExemplarLoader {

    private static final String FRAUD_PAYLOAD_KEY = "fraud";
    private static final int FEATURE_COUNT = 16;
    private static final double AMT_RATIO_CAP = 50.0;
    private static final double MISSING_DELTA = -1.0;
    private static final long SECONDS_PER_DAY = 86_400L;
    private static final double TWO_PI = 2.0 * Math.PI;
    private static final int UPSERT_BATCH = 256;
    private static final int DEFAULT_MAX_EXEMPLARS = 45_000;

    private static final Set<String> FREE_MAIL = Set.of(
            "gmail.com", "yahoo.com", "hotmail.com", "outlook.com", "aol.com",
            "anonymous.com", "mail.com", "protonmail.com", "icloud.com", "live.com");

    private M2ExemplarLoader() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        Path csv = Path.of(require(opts, "--csv"));
        Path modelsDir = Path.of(opts.getOrDefault("--models-dir",
                "/Users/achilleaseftychiou/Documents/Projects/diakrisis/diakrisis-models"));
        String host = opts.getOrDefault("--qdrant-host", "localhost");
        int port = Integer.parseInt(opts.getOrDefault("--qdrant-port", "6334"));
        String collection = opts.getOrDefault("--collection", "fraud_exemplars");
        int maxExemplars = Integer.parseInt(
                opts.getOrDefault("--max-exemplars", Integer.toString(DEFAULT_MAX_EXEMPLARS)));
        boolean recreate = opts.containsKey("--recreate");

        if (!Files.exists(csv)) {
            throw new IOException("train_transaction.csv not found: " + csv);
        }
        M2Scaler scaler = M2Scaler.load(modelsDir);
        if (scaler.length() != FEATURE_COUNT) {
            throw new IllegalStateException("scaler expects " + scaler.length() + " columns, need " + FEATURE_COUNT);
        }

        System.out.printf(Locale.ROOT, "M2 exemplar loader: csv=%s models=%s qdrant=%s:%d collection=%s "
                        + "maxExemplars=%d recreate=%b%n",
                csv, modelsDir, host, port, collection, maxExemplars, recreate);

        // First pass: stream the CSV, derive features, select a bounded stratified exemplar set.
        List<Exemplar> exemplars = buildExemplars(csv, maxExemplars);
        long fraudCount = exemplars.stream().filter(e -> e.label == 1).count();
        System.out.printf(Locale.ROOT, "Built %d exemplars (%d fraud, %d legit).%n",
                exemplars.size(), fraudCount, exemplars.size() - fraudCount);

        // Write the KDTree parity table (raw label,f0..f15) so the in-JVM fallback can index the SAME
        // exemplar set Qdrant holds. Default target is a DEDICATED file (m2/exemplars.kdtree.csv), NOT
        // the auto-loaded m2/exemplars.csv: the canonical models dir intentionally ships without the
        // latter so the KDTree fallback is dormant (M2=0) and the golden-path baseline is undisturbed.
        // An operator points diakrisis.m2.kdtree-table at this file to run the live KDTree fallback.
        Path kdtreeOut = Path.of(opts.getOrDefault("--kdtree-out",
                modelsDir.resolve("m2").resolve("exemplars.kdtree.csv").toString()));
        writeKdTreeTable(kdtreeOut, exemplars);
        System.out.printf(Locale.ROOT, "Wrote KDTree parity table: %s (%d rows).%n",
                kdtreeOut, exemplars.size());

        // Upsert into Qdrant (standardize + L2-normalize each vector with the production scaler).
        long pointCount = upsertToQdrant(host, port, collection, recreate, scaler, exemplars);
        System.out.printf(Locale.ROOT, "Qdrant collection '%s' now holds %d points.%n", collection, pointCount);
        if (pointCount <= 0) {
            throw new IllegalStateException("Qdrant collection is empty after upsert");
        }
    }

    // The IEEE-CIS train split: ~590,540 rows, ~20,663 fraud → a true fraud base rate of ~3.5%. The
    // exemplar set MUST preserve this class balance, otherwise the M2 fraud-neighbour share is biased
    // (an over-sampled-fraud index reports a spuriously high share for ordinary transactions). We
    // therefore sample EACH class independently with its own stride across the WHOLE timeline so the
    // exemplar mix mirrors the real distribution and the k-NN neighbourhood density is faithful.
    private static final long TRAIN_LEGIT_ROWS = 569_877L;
    private static final long TRAIN_FRAUD_ROWS = 20_663L;
    private static final long TRAIN_TOTAL_ROWS = TRAIN_LEGIT_ROWS + TRAIN_FRAUD_ROWS;

    /**
     * Stream the ENTIRE time-sorted CSV once, computing the 16 raw features per row (FEATURE_SPEC) and
     * keeping a bounded, class-balance-preserving exemplar set: each class is sampled with its own
     * deterministic stride so the kept fraud fraction matches the true ~3.5% base rate, capped at
     * {@code maxExemplars}. The {@code amt_ratio} expanding mean is maintained over EVERY prior row of
     * each {@code card1} (never short-circuited), so a sampled row's ratio is exact regardless of
     * sampling.
     */
    private static List<Exemplar> buildExemplars(Path csv, int maxExemplars) throws IOException {
        List<Exemplar> selected = new ArrayList<>(Math.min(maxExemplars, DEFAULT_MAX_EXEMPLARS));
        Map<String, double[]> card1Running = new HashMap<>(); // card1 -> {count, sum}

        // Split the exemplar budget by the true base rate, then derive a per-class stride that spreads
        // the sample evenly across the whole file (every Nth row of that class).
        long fraudBudget = Math.max(1L, Math.round(maxExemplars * (double) TRAIN_FRAUD_ROWS / TRAIN_TOTAL_ROWS));
        long legitBudget = Math.max(1L, maxExemplars - fraudBudget);
        long fraudStride = Math.max(1L, TRAIN_FRAUD_ROWS / fraudBudget);
        long legitStride = Math.max(1L, TRAIN_LEGIT_ROWS / legitBudget);

        long fraudSeen = 0;
        long legitSeen = 0;
        long fraudKept = 0;
        long legitKept = 0;

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();

        try (Reader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8);
             CSVParser parser = CSVParser.parse(reader, format)) {
            for (CSVRecord record : parser) {
                int label = "1".equals(get(record, "isFraud")) ? 1 : 0;
                String card1 = get(record, "card1");
                double amt = parseDoubleOrDefault(get(record, "TransactionAmt"), 0.0);

                // Expanding mean over ALL prior rows of this card1 (exclude current) for amt_ratio —
                // updated for EVERY row so the running mean is exact even for unsampled rows.
                double[] running = card1Running.computeIfAbsent(card1 == null ? "" : card1,
                        key -> new double[2]);
                double priorCount = running[0];
                double priorSum = running[1];
                double amtRatio;
                if (priorCount == 0.0) {
                    amtRatio = 1.0;
                } else {
                    double priorMean = priorSum / priorCount;
                    amtRatio = priorMean <= 0.0 ? 1.0 : Math.min(amt / priorMean, AMT_RATIO_CAP);
                }
                running[0] = priorCount + 1.0;
                running[1] = priorSum + amt;

                boolean keep;
                if (label == 1) {
                    fraudSeen++;
                    keep = (fraudSeen % fraudStride == 0) && fraudKept < fraudBudget;
                    if (keep) {
                        fraudKept++;
                    }
                } else {
                    legitSeen++;
                    keep = (legitSeen % legitStride == 0) && legitKept < legitBudget;
                    if (keep) {
                        legitKept++;
                    }
                }
                if (keep) {
                    selected.add(new Exemplar(label, deriveFeatures(record, amt, amtRatio)));
                }
                // Do NOT break early: stream the whole file so both class strides cover the full
                // timeline and the amt_ratio running means stay exact.
            }
        }
        return selected;
    }

    /** Derive the 16 raw features for a row per FEATURE_SPEC.md (same order as Features.COLUMNS). */
    private static double[] deriveFeatures(CSVRecord record, double amt, double amtRatio) {
        double[] v = new double[FEATURE_COUNT];

        // 1. amt_log = ln(1 + TransactionAmt)
        v[0] = Math.log1p(Math.max(0.0, amt));

        // 2-5. cyclic hour/dow from TransactionDT (seconds since the dataset epoch).
        long dt = (long) parseDoubleOrDefault(get(record, "TransactionDT"), 0.0);
        double hour = (double) Math.floorMod(dt, SECONDS_PER_DAY) / 3600.0;
        long dow = Math.floorMod(Math.floorDiv(dt, SECONDS_PER_DAY), 7L);
        v[1] = Math.sin(TWO_PI * hour / 24.0);
        v[2] = Math.cos(TWO_PI * hour / 24.0);
        v[3] = Math.sin(TWO_PI * dow / 7.0);
        v[4] = Math.cos(TWO_PI * dow / 7.0);

        // 6-8. velocity counts C1/C13/C14 → ln(1 + count), null → 0 before log.
        v[5] = Math.log1p(parseDoubleOrDefault(get(record, "C1"), 0.0));
        v[6] = Math.log1p(parseDoubleOrDefault(get(record, "C13"), 0.0));
        v[7] = Math.log1p(parseDoubleOrDefault(get(record, "C14"), 0.0));

        // 9-13. recency deltas D1/D4/D10/D15; null → −1 with a miss count.
        Double d1 = parseNullableDouble(get(record, "D1"));
        Double d4 = parseNullableDouble(get(record, "D4"));
        Double d10 = parseNullableDouble(get(record, "D10"));
        Double d15 = parseNullableDouble(get(record, "D15"));
        v[8] = countMissing(d1, d4, d10, d15);
        v[9] = d1 != null ? d1 : MISSING_DELTA;
        v[10] = d4 != null ? d4 : MISSING_DELTA;
        v[11] = d10 != null ? d10 : MISSING_DELTA;
        v[12] = d15 != null ? d15 : MISSING_DELTA;

        // 14. amt_ratio (expanding mean per card1, computed by the caller).
        v[13] = amtRatio;

        // 15-16. email flags from P_emaildomain.
        String email = get(record, "P_emaildomain");
        boolean missing = email == null || email.isBlank();
        v[14] = missing ? 1.0 : 0.0;
        v[15] = (!missing && FREE_MAIL.contains(email.toLowerCase(Locale.ROOT))) ? 1.0 : 0.0;

        return v;
    }

    /** Write the raw exemplar table the KDTree fallback loads: a header then {@code label,f0..f15}. */
    private static void writeKdTreeTable(Path path, List<Exemplar> exemplars) throws IOException {
        Files.createDirectories(path.getParent());
        StringBuilder header = new StringBuilder("label");
        for (int i = 0; i < FEATURE_COUNT; i++) {
            header.append(",f").append(i);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write(header.toString());
            writer.newLine();
            for (Exemplar exemplar : exemplars) {
                StringBuilder row = new StringBuilder();
                row.append(exemplar.label);
                for (double f : exemplar.raw) {
                    row.append(',').append(formatDouble(f));
                }
                writer.write(row.toString());
                writer.newLine();
            }
        }
    }

    /**
     * Recreate (optional) the collection as 16-dim cosine, then upsert every exemplar as a point whose
     * vector is the scaler-transformed (standardized + L2-normalized) feature vector and whose payload
     * is {@code {fraud: label}}. Returns the collection's point count after the upsert.
     */
    private static long upsertToQdrant(String host, int port, String collection, boolean recreate,
                                       M2Scaler scaler, List<Exemplar> exemplars) throws Exception {
        QdrantClient client = new QdrantClient(
                QdrantGrpcClient.newBuilder(host, port, false)
                        .withTimeout(Duration.ofSeconds(30))
                        .build());
        try {
            boolean exists = client.collectionExistsAsync(collection).get(30, TimeUnit.SECONDS);
            if (recreate && exists) {
                client.deleteCollectionAsync(collection).get(60, TimeUnit.SECONDS);
                exists = false;
                System.out.printf(Locale.ROOT, "Dropped existing collection '%s'.%n", collection);
            }
            if (!exists) {
                VectorParams params = VectorParams.newBuilder()
                        .setSize(FEATURE_COUNT)
                        .setDistance(Distance.Cosine)
                        .build();
                client.createCollectionAsync(CreateCollection.newBuilder()
                        .setCollectionName(collection)
                        .setVectorsConfig(VectorsConfig.newBuilder().setParams(params).build())
                        .build()).get(60, TimeUnit.SECONDS);
                System.out.printf(Locale.ROOT, "Created collection '%s' (16-dim cosine).%n", collection);
            }

            List<PointStruct> batch = new ArrayList<>(UPSERT_BATCH);
            long id = 0;
            for (Exemplar exemplar : exemplars) {
                double[] unit = scaler.transform(exemplar.raw);
                List<Float> vector = new ArrayList<>(FEATURE_COUNT);
                for (double component : unit) {
                    vector.add((float) component);
                }
                Value fraudValue = ValueFactory.value(exemplar.label);
                PointStruct point = PointStruct.newBuilder()
                        .setId(PointIdFactory.id(id++))
                        .setVectors(VectorsFactory.vectors(vector))
                        .putPayload(FRAUD_PAYLOAD_KEY, fraudValue)
                        .build();
                batch.add(point);
                if (batch.size() >= UPSERT_BATCH) {
                    client.upsertAsync(collection, batch).get(60, TimeUnit.SECONDS);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                client.upsertAsync(collection, batch).get(60, TimeUnit.SECONDS);
            }
            return client.countAsync(collection).get(30, TimeUnit.SECONDS);
        } finally {
            client.close();
        }
    }

    // ---- CSV / parsing helpers ----

    private static String get(CSVRecord record, String column) {
        if (!record.isMapped(column)) {
            return null;
        }
        String value = record.get(column);
        if (value == null || value.isEmpty() || "NaN".equals(value)) {
            return null;
        }
        return value;
    }

    private static double parseDoubleOrDefault(String raw, double fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Double parseNullableDouble(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int countMissing(Double... values) {
        int n = 0;
        for (Double d : values) {
            if (d == null) {
                n++;
            }
        }
        return n;
    }

    private static String formatDouble(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                continue;
            }
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                opts.put(arg, args[++i]);
            } else {
                opts.put(arg, "");
            }
        }
        return opts;
    }

    private static String require(Map<String, String> opts, String key) {
        String value = opts.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required arg " + key);
        }
        return value;
    }

    /** A labelled exemplar: the raw 16-feature vector (pre-scaler) and its fraud label (0|1). */
    private record Exemplar(int label, double[] raw) {
    }
}
