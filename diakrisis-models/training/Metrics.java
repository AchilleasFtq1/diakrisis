import java.util.Arrays;

/** Ranking metrics shared by SmileCheck and TrainM1. */
public final class Metrics {

    private Metrics() { }

    /** Average precision = area under the precision-recall curve. */
    public static double averagePrecision(int[] labels, double[] scores) {
        Integer[] order = sortedByScoreDesc(labels.length, scores);
        int positives = Arrays.stream(labels).sum();
        int hits = 0;
        double sum = 0.0;
        for (int rank = 0; rank < order.length; rank++) {
            if (labels[order[rank]] == 1) {
                hits++;
                sum += (double) hits / (rank + 1);
            }
        }
        return sum / positives;
    }

    /** ROC-AUC via the Mann-Whitney rank statistic (ties get average rank). */
    public static double rocAuc(int[] labels, double[] scores) {
        Integer[] order = sortedByScoreDesc(labels.length, scores);
        int n = labels.length;
        double[] ranks = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && scores[order[j + 1]] == scores[order[i]]) j++;
            double avgRank = (i + j) / 2.0 + 1;
            for (int k = i; k <= j; k++) ranks[order[k]] = avgRank;
            i = j + 1;
        }
        long pos = Arrays.stream(labels).filter(l -> l == 1).count();
        long neg = n - pos;
        double rankSumPosDesc = 0.0;
        for (int k = 0; k < n; k++) {
            if (labels[k] == 1) rankSumPosDesc += ranks[k];
        }
        double rankSumPosAsc = pos * (n + 1.0) - rankSumPosDesc;
        return (rankSumPosAsc - pos * (pos + 1) / 2.0) / ((double) pos * neg);
    }

    private static Integer[] sortedByScoreDesc(int n, double[] scores) {
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(scores[b], scores[a]));
        return order;
    }
}
