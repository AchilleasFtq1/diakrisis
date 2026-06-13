package com.cy.diakritis.engine.m2;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The §9.2 PRODUCTION {@link ExemplarIndex}: a live cosine k-NN over the Qdrant {@code fraud_exemplars}
 * collection (gRPC :6334). The query vector is the already standardized + L2-normalized 16-feature M1
 * vector (the same {@link M2Scaler} transform every exemplar was loaded with). Qdrant returns each
 * neighbour's cosine <em>similarity</em> in {@code score}; the cosine <em>distance</em> the contract's
 * weighting uses is {@code 1 - score}. The M2 signal is the distance-weighted fraud share among the k
 * nearest neighbours: {@code Σ(w·fraud)/Σw} with {@code w = 1 / (cosineDistance + 1e-6)} — identical to
 * the {@link KdTreeExemplarIndex} formula, so the two backends agree on the same vector within
 * floating-point tolerance.
 *
 * <p>The payload key {@code fraud} (an integer {@code 0|1}) carries the label. A query that throws,
 * times out, or returns no neighbours yields {@code 0.0} — M2 is additive and never a hard dependency,
 * so a transient Qdrant fault degrades the signal rather than the decision. Hard unreachability is
 * decided once at construction (see {@link QdrantExemplarIndexFactory}); per-call faults degrade here.
 */
public final class QdrantExemplarIndex implements ExemplarIndex {

    private static final Logger log = LoggerFactory.getLogger(QdrantExemplarIndex.class);
    private static final String FRAUD_PAYLOAD_KEY = "fraud";
    private static final double DISTANCE_EPS = 1e-6;

    private final QdrantClient client;
    private final String collection;
    private final long pointCount;
    private final Duration queryTimeout;

    /**
     * @param client       a connected Qdrant gRPC client (owned by the caller / factory; closed by it)
     * @param collection   the collection name (e.g. {@code fraud_exemplars})
     * @param pointCount   the collection's point count, read once at construction (must be {@code > 0})
     * @param queryTimeout the hard per-query budget; an overrun degrades that query's share to 0
     */
    public QdrantExemplarIndex(QdrantClient client, String collection, long pointCount, Duration queryTimeout) {
        if (client == null || collection == null || collection.isBlank()) {
            throw new IllegalArgumentException("client and collection are required");
        }
        if (pointCount <= 0) {
            throw new IllegalArgumentException("collection must hold at least one point");
        }
        if (queryTimeout == null || queryTimeout.isZero() || queryTimeout.isNegative()) {
            throw new IllegalArgumentException("queryTimeout must be positive");
        }
        this.client = client;
        this.collection = collection;
        this.pointCount = pointCount;
        this.queryTimeout = queryTimeout;
    }

    @Override
    public boolean isAvailable() {
        return pointCount > 0;
    }

    @Override
    public double fraudNeighborShare(double[] queryVector, int k) {
        if (queryVector == null || queryVector.length == 0 || k <= 0) {
            return 0.0;
        }
        int kEffective = (int) Math.min(k, pointCount);
        if (kEffective <= 0) {
            return 0.0;
        }
        try {
            List<ScoredPoint> neighbours = search(queryVector, kEffective);
            return weightedFraudShare(neighbours);
        } catch (TimeoutException e) {
            log.warn("Qdrant M2 query timed out after {} ms; degrading this signal to 0.",
                    queryTimeout.toMillis());
            return 0.0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Qdrant M2 query interrupted; degrading this signal to 0.");
            return 0.0;
        } catch (ExecutionException | RuntimeException e) {
            log.warn("Qdrant M2 query failed; degrading this signal to 0. cause={}", e.toString());
            return 0.0;
        }
    }

    private List<ScoredPoint> search(double[] queryVector, int kEffective)
            throws InterruptedException, ExecutionException, TimeoutException {
        SearchPoints.Builder request = SearchPoints.newBuilder()
                .setCollectionName(collection)
                .setLimit(kEffective)
                .setWithPayload(WithPayloadSelectorFactory.enable(true));
        for (double component : queryVector) {
            request.addVector((float) component);
        }
        return client.searchAsync(request.build(), queryTimeout)
                .get(queryTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Distance-weighted fraud share {@code Σ(w·fraud)/Σw} over the returned neighbours, with the cosine
     * distance recovered from Qdrant's cosine similarity {@code score} as {@code 1 - score} (clamped to
     * {@code [0,2]}). A neighbour missing the {@code fraud} payload is treated as legit (label 0).
     */
    private static double weightedFraudShare(List<ScoredPoint> neighbours) {
        if (neighbours == null || neighbours.isEmpty()) {
            return 0.0;
        }
        double weightedFraud = 0.0;
        double weightSum = 0.0;
        for (ScoredPoint neighbour : neighbours) {
            double cosineDistance = Math.max(0.0, Math.min(2.0, 1.0 - neighbour.getScore()));
            double weight = 1.0 / (cosineDistance + DISTANCE_EPS);
            int label = fraudLabel(neighbour);
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

    private static int fraudLabel(ScoredPoint neighbour) {
        JsonWithInt.Value value = neighbour.getPayloadMap().get(FRAUD_PAYLOAD_KEY);
        if (value == null) {
            return 0;
        }
        long asInteger = value.getIntegerValue();
        return asInteger >= 1 ? 1 : 0;
    }
}
