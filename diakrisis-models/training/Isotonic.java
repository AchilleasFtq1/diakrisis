import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * Isotonic regression via pool-adjacent-violators, as a step function over
 * raw scores. Mirrors sklearn's IsotonicRegression(out_of_bounds="clip") for
 * the calibration use case: fit on (raw score, 0/1 label), predict by locating
 * the step whose threshold is the largest <= the query score.
 */
public final class Isotonic {

    private final double[] thresholds;   // ascending raw-score block boundaries
    private final double[] values;       // calibrated value per block (non-decreasing)

    private Isotonic(double[] thresholds, double[] values) {
        this.thresholds = thresholds;
        this.values = values;
    }

    public static Isotonic fit(double[] scores, int[] labels) {
        int n = scores.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble(i -> scores[i]));

        // PAV: blocks of (sum, weight, leftScore), merged while monotonicity is violated
        double[] sum = new double[n], weight = new double[n], left = new double[n];
        int top = 0;
        for (int k = 0; k < n; k++) {
            sum[top] = labels[order[k]];
            weight[top] = 1.0;
            left[top] = scores[order[k]];
            top++;
            while (top > 1 && sum[top - 2] / weight[top - 2] >= sum[top - 1] / weight[top - 1]) {
                sum[top - 2] += sum[top - 1];
                weight[top - 2] += weight[top - 1];
                top--;
            }
        }
        double[] thresholds = Arrays.copyOf(left, top);
        double[] values = new double[top];
        for (int b = 0; b < top; b++) values[b] = sum[b] / weight[b];
        return new Isotonic(thresholds, values);
    }

    public double predict(double score) {
        int lo = Arrays.binarySearch(thresholds, score);
        if (lo >= 0) return values[lo];
        int insertion = -lo - 1;
        return values[Math.max(insertion - 1, 0)];       // clip below range to first block
    }

    public double[] predict(double[] scores) {
        double[] out = new double[scores.length];
        for (int i = 0; i < scores.length; i++) out[i] = predict(scores[i]);
        return out;
    }

    public void save(Path path) throws IOException {
        StringBuilder sb = new StringBuilder("threshold,value\n");
        for (int i = 0; i < thresholds.length; i++) {
            sb.append(String.format(Locale.ROOT, "%.9f,%.9f%n", thresholds[i], values[i]));
        }
        Files.writeString(path, sb.toString());
    }
}
