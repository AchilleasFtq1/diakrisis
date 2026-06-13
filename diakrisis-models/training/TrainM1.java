import smile.classification.GradientTreeBoost;
import smile.data.DataFrame;
import smile.data.formula.Formula;
import smile.data.vector.IntVector;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Full M1 training in the event stack (Smile GBT), producing the pretrained
 * artifact set under models/m1/:
 *   m1.model            — serialized GradientTreeBoost
 *   isotonic.csv        — PAV calibration step function (threshold,value)
 *   percentiles.csv     — calibrated train-score percentiles 0..100
 *   columns.txt         — feature order contract (must match Features at serve)
 *   val_scores.csv      — calibrated score per validation row (for case tests)
 *   metrics_m1_smile.json
 *
 * Eval mirrors the Python suite: PR-AUC/ROC-AUC on the untouched val fold,
 * confusion at 0.3/0.5/0.8, calibration bins, ULB benchmark via the identical
 * discipline. Config: 300 trees, depth 6, maxNodes 31,
 * nodeSize 20, shrinkage 0.1 (sklearn parity: 0.4186 vs 0.4229 PR-AUC).
 */
public final class TrainM1 {

    private static final int TREES = 300;
    private static final int MAX_DEPTH = 6;
    private static final int MAX_NODES = 31;
    private static final int NODE_SIZE = 20;
    private static final double SHRINKAGE = 0.1;

    public static void main(String[] args) throws IOException {
        Path features = Path.of("../python/artifacts/features_export.csv");
        Path ulbCsv = Path.of("../data/raw/ulb/creditcard.csv");
        Path outDir = Path.of("../models/m1");
        Files.createDirectories(outDir);

        // ---- load exported feature matrix with fold labels ----
        List<double[]> trX = new ArrayList<>(420_000);
        List<double[]> calX = new ArrayList<>(90_000);
        List<double[]> valX = new ArrayList<>(90_000);
        List<Integer> trY = new ArrayList<>(), calY = new ArrayList<>(), valY = new ArrayList<>();
        String[] names;
        try (BufferedReader r = Files.newBufferedReader(features)) {
            String[] header = r.readLine().split(",");
            int nf = header.length - 2;
            names = Arrays.copyOf(header, nf);
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split(",");
                double[] x = new double[nf];
                for (int i = 0; i < nf; i++) x[i] = Double.parseDouble(p[i]);
                int label = Integer.parseInt(p[nf]);
                switch (p[nf + 1]) {
                    case "train" -> { trX.add(x); trY.add(label); }
                    case "cal"   -> { calX.add(x); calY.add(label); }
                    default      -> { valX.add(x); valY.add(label); }
                }
            }
        }
        System.out.printf("train %,d | cal %,d | val %,d | %d features%n",
                trX.size(), calX.size(), valX.size(), names.length);

        // ---- train ----
        long t0 = System.currentTimeMillis();
        DataFrame train = frame(trX, trY, names, "isFraud");
        GradientTreeBoost model = GradientTreeBoost.fit(Formula.lhs("isFraud"), train,
                TREES, MAX_DEPTH, MAX_NODES, NODE_SIZE, SHRINKAGE, 1.0);
        double trainSeconds = (System.currentTimeMillis() - t0) / 1000.0;
        System.out.printf("trained in %.1fs%n", trainSeconds);

        // ---- calibrate on the calibration fold (PAV isotonic) ----
        double[] calRaw = score(model, calX, names);
        Isotonic iso = Isotonic.fit(calRaw, toInts(calY));

        // ---- evaluate on the untouched validation fold ----
        double[] valCal = iso.predict(score(model, valX, names));
        int[] yv = toInts(valY);
        double pr = Metrics.averagePrecision(yv, valCal);
        double roc = Metrics.rocAuc(yv, valCal);
        System.out.printf("M1 Smile (calibrated): PR-AUC %.4f  ROC-AUC %.4f%n", pr, roc);

        StringBuilder ops = new StringBuilder();
        for (double thr : new double[]{0.3, 0.5, 0.8}) {
            int tp = 0, fp = 0, fn = 0;
            for (int i = 0; i < yv.length; i++) {
                boolean flag = valCal[i] >= thr;
                if (flag && yv[i] == 1) tp++;
                else if (flag) fp++;
                else if (yv[i] == 1) fn++;
            }
            double prec = tp / (double) Math.max(tp + fp, 1);
            double rec = tp / (double) Math.max(tp + fn, 1);
            System.out.printf("  operating point %.1f: precision %.3f  recall %.3f  (fp=%d, fn=%d)%n",
                    thr, prec, rec, fp, fn);
            ops.append(String.format(Locale.ROOT,
                    "    \"op_%.1f\": {\"precision\": %.3f, \"recall\": %.3f, \"fp\": %d, \"fn\": %d},%n",
                    thr, prec, rec, fp, fn));
        }

        System.out.println("  calibration (predicted bin -> observed):");
        StringBuilder calJson = new StringBuilder();
        double[] edges = {0, .1, .3, .5, .7, .9, 1.0000001};
        for (int b = 0; b < edges.length - 1; b++) {
            int n = 0, posCount = 0;
            for (int i = 0; i < yv.length; i++) {
                if (valCal[i] >= edges[b] && valCal[i] < edges[b + 1]) { n++; posCount += yv[i]; }
            }
            if (n > 0) {
                System.out.printf("    [%.1f-%.1f): %6d txns, observed %.3f%n",
                        edges[b], Math.min(edges[b + 1], 1.0), n, posCount / (double) n);
                calJson.append(String.format(Locale.ROOT,
                        "    \"bin_%.1f\": {\"n\": %d, \"observed\": %.4f},%n",
                        edges[b], n, posCount / (double) n));
            }
        }

        // ---- ULB benchmark, identical discipline ----
        double[] ulb = ulbBenchmark(ulbCsv);
        System.out.printf("ULB Smile (calibrated): PR-AUC %.4f  ROC-AUC %.4f%n", ulb[0], ulb[1]);

        // ---- persist artifacts ----
        try (ObjectOutputStream os = new ObjectOutputStream(Files.newOutputStream(outDir.resolve("m1.model")))) {
            os.writeObject(model);
        }
        iso.save(outDir.resolve("isotonic.csv"));
        Files.writeString(outDir.resolve("columns.txt"), String.join("\n", names) + "\n");

        double[] trainCal = iso.predict(score(model, trX, names));
        Arrays.sort(trainCal);
        StringBuilder pct = new StringBuilder("percentile,score\n");
        for (int q = 0; q <= 100; q++) {
            int idx = Math.min((int) Math.round(q / 100.0 * (trainCal.length - 1)), trainCal.length - 1);
            pct.append(q).append(',').append(String.format(Locale.ROOT, "%.6f", trainCal[idx])).append('\n');
        }
        Files.writeString(outDir.resolve("percentiles.csv"), pct.toString());

        StringBuilder vs = new StringBuilder("val_row,calibrated_score,isFraud\n");
        for (int i = 0; i < valCal.length; i++) {
            vs.append(i).append(',').append(String.format(Locale.ROOT, "%.6f", valCal[i]))
              .append(',').append(yv[i]).append('\n');
        }
        Files.writeString(outDir.resolve("val_scores.csv"), vs.toString());

        Files.writeString(outDir.resolve("metrics_m1_smile.json"), String.format(Locale.ROOT, """
                {
                  "run": "smile-pretrain",
                  "disclaimer": "built and committed inside the event window",
                  "config": {"trees": %d, "max_depth": %d, "max_nodes": %d, "node_size": %d, "shrinkage": %.2f},
                  "train_seconds": %.1f,
                  "m1": {"pr_auc": %.4f, "roc_auc": %.4f},
                %s%s  "ulb_benchmark": {"pr_auc": %.4f, "roc_auc": %.4f}
                }
                """, TREES, MAX_DEPTH, MAX_NODES, NODE_SIZE, SHRINKAGE, trainSeconds,
                pr, roc, ops, calJson, ulb[0], ulb[1]));
        System.out.println("artifacts -> " + outDir.toAbsolutePath().normalize());
    }

    // ---------- helpers ----------

    private static DataFrame frame(List<double[]> x, List<Integer> y, String[] names, String label) {
        return DataFrame.of(x.toArray(double[][]::new), names)
                .merge(IntVector.of(label, y.stream().mapToInt(Integer::intValue).toArray()));
    }

    private static double[] score(GradientTreeBoost model, List<double[]> x, String[] names) {
        DataFrame df = DataFrame.of(x.toArray(double[][]::new), names);
        double[] out = new double[df.size()];
        double[] post = new double[2];
        for (int i = 0; i < df.size(); i++) {
            model.predict(df.get(i), post);
            out[i] = post[1];
        }
        return out;
    }

    private static int[] toInts(List<Integer> y) {
        return y.stream().mapToInt(Integer::intValue).toArray();
    }

    private static double[] ulbBenchmark(Path csv) throws IOException {
        List<double[]> x = new ArrayList<>(290_000);
        List<Integer> y = new ArrayList<>(290_000);
        String[] names;
        try (BufferedReader r = Files.newBufferedReader(csv)) {
            String[] header = r.readLine().split(",");
            int nf = header.length - 1;                  // last col = Class
            names = Arrays.copyOf(header, nf);
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split(",");
                double[] row = new double[nf];
                for (int i = 0; i < nf; i++) row[i] = Double.parseDouble(p[i]);
                x.add(row);
                y.add(Integer.parseInt(p[nf]));
            }
        }
        // already Time-ordered; same 70/15/15 discipline
        int n = x.size(), iTr = (int) (n * 0.70), iCal = (int) (n * 0.85);
        // Smile GBT has no sample weights; at ULB's 1:578 imbalance an unweighted
        // fit degenerates to a constant. Oversample fraud x60 (~9.4%) — the
        // tree-model equivalent of the Python pipeline's sample weights. Disclosed.
        List<double[]> trXb = new ArrayList<>(x.subList(0, iTr));
        List<Integer> trYb = new ArrayList<>(y.subList(0, iTr));
        for (int i = 0; i < iTr; i++) {
            if (y.get(i) == 1) {
                for (int d = 0; d < 59; d++) { trXb.add(x.get(i)); trYb.add(1); }
            }
        }
        DataFrame train = frame(trXb, trYb, names, "Class");
        GradientTreeBoost m = GradientTreeBoost.fit(Formula.lhs("Class"), train,
                TREES, MAX_DEPTH, MAX_NODES, NODE_SIZE, SHRINKAGE, 1.0);
        Isotonic iso = Isotonic.fit(score(m, x.subList(iTr, iCal), names),
                toInts(y.subList(iTr, iCal)));
        double[] valCal = iso.predict(score(m, x.subList(iCal, n), names));
        int[] yv = toInts(y.subList(iCal, n));
        return new double[]{Metrics.averagePrecision(yv, valCal), Metrics.rocAuc(yv, valCal)};
    }
}
