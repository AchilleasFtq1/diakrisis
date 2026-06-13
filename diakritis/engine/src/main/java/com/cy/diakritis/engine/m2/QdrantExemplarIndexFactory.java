package com.cy.diakritis.engine.m2;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Builds the §9.2 M2 backend per {@code diakrisis.m2.backend} and wires it into an {@link M2Scorer}.
 *
 * <p>Resolution (the contract's "qdrant when reachable else kdtree, default qdrant"):
 * <ul>
 *   <li>{@link M2Backend#QDRANT} / {@link M2Backend#AUTO} — connect to Qdrant (gRPC), read the
 *       {@code fraud_exemplars} point count once; if it is reachable and non-empty, serve M2 from the
 *       live {@link QdrantExemplarIndex}. Otherwise fall back to the in-JVM KDTree.</li>
 *   <li>{@link M2Backend#KDTREE} — skip Qdrant entirely and serve M2 from the in-JVM KDTree over
 *       {@code m2/exemplars.csv}.</li>
 * </ul>
 *
 * <p>The query vector and the exemplars share one {@link M2Scaler} transform regardless of backend, so
 * the two indexes return the same distance-weighted fraud share on the same vector within
 * floating-point tolerance. A Qdrant that becomes unreachable AFTER boot degrades per-query inside
 * {@link QdrantExemplarIndex} (the signal scores 0, the decision is unaffected); a Qdrant that is
 * unreachable AT boot is replaced wholesale by the KDTree here.
 */
public final class QdrantExemplarIndexFactory {

    private static final Logger log = LoggerFactory.getLogger(QdrantExemplarIndexFactory.class);

    private QdrantExemplarIndexFactory() {
    }

    /** Immutable Qdrant connection settings (bound from {@code diakrisis.m2.qdrant.*}). */
    public record QdrantConfig(String host, int port, boolean useTls, String collection,
                               Duration connectTimeout, Duration queryTimeout) {

        public QdrantConfig {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("qdrant host is required");
            }
            if (port <= 0 || port > 65_535) {
                throw new IllegalArgumentException("qdrant port out of range: " + port);
            }
            if (collection == null || collection.isBlank()) {
                throw new IllegalArgumentException("qdrant collection is required");
            }
            if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
                throw new IllegalArgumentException("connectTimeout must be positive");
            }
            if (queryTimeout == null || queryTimeout.isNegative() || queryTimeout.isZero()) {
                throw new IllegalArgumentException("queryTimeout must be positive");
            }
        }
    }

    /**
     * Outcome of M2 backend resolution: the constructed scorer plus the backend that actually backs it
     * (which may differ from the requested one when Qdrant is unreachable and the factory fell back).
     * The Qdrant {@link QdrantClient} (when one was opened) is carried so the owner can close it on
     * shutdown; it is {@code null} for the KDTree/empty outcomes.
     */
    public record Resolution(M2Scorer scorer, M2Backend effectiveBackend, long qdrantPointCount,
                             QdrantClient qdrantClient) {
    }

    /**
     * Resolve and build the M2 scorer.
     *
     * @param requested  the configured backend ({@code qdrant|kdtree|auto})
     * @param scaler     the loaded {@link M2Scaler} (required for both backends; if {@code null} M2 is empty)
     * @param kdTreeIndex the already-built in-JVM KDTree index (the fallback; may be empty)
     * @param qdrant     Qdrant connection settings
     */
    public static Resolution resolve(M2Backend requested, M2Scaler scaler, ExemplarIndex kdTreeIndex,
                                     QdrantConfig qdrant) {
        if (scaler == null) {
            log.info("M2 scaler unavailable; M2 disabled (empty index).");
            return new Resolution(M2Scorer.of(null, ExemplarIndex.empty()), M2Backend.KDTREE, 0L, null);
        }
        ExemplarIndex fallback = kdTreeIndex == null ? ExemplarIndex.empty() : kdTreeIndex;

        if (requested == M2Backend.KDTREE) {
            log.info("M2 backend = KDTREE (configured); Qdrant not contacted. available={}",
                    fallback.isAvailable());
            return new Resolution(M2Scorer.of(scaler, fallback), M2Backend.KDTREE, 0L, null);
        }

        // QDRANT or AUTO: try the live backend, degrade to KDTree if unreachable/empty.
        QdrantClient client = null;
        try {
            client = openClient(qdrant);
            long pointCount = client.countAsync(qdrant.collection(), qdrant.queryTimeout())
                    .get(qdrant.connectTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (pointCount <= 0) {
                log.warn("M2 backend = qdrant requested but collection '{}' is empty ({} pts); "
                        + "falling back to KDTREE.", qdrant.collection(), pointCount);
                closeQuietly(client);
                return new Resolution(M2Scorer.of(scaler, fallback), M2Backend.KDTREE, 0L, null);
            }
            QdrantExemplarIndex index =
                    new QdrantExemplarIndex(client, qdrant.collection(), pointCount, qdrant.queryTimeout());
            log.info("M2 backend = QDRANT (live): collection '{}' on {}:{} holds {} exemplar points.",
                    qdrant.collection(), qdrant.host(), qdrant.port(), pointCount);
            return new Resolution(M2Scorer.of(scaler, index), M2Backend.QDRANT, pointCount, client);
        } catch (Exception e) {
            closeQuietly(client);
            log.warn("M2 backend = qdrant requested but Qdrant not reachable at {}:{} ({}); "
                            + "falling back to KDTREE (available={}).",
                    qdrant.host(), qdrant.port(), e.toString(), fallback.isAvailable());
            return new Resolution(M2Scorer.of(scaler, fallback), M2Backend.KDTREE, 0L, null);
        }
    }

    /** Open a Qdrant gRPC client against the given settings (caller owns the lifecycle). */
    public static QdrantClient openClient(QdrantConfig qdrant) {
        QdrantGrpcClient grpc = QdrantGrpcClient
                .newBuilder(qdrant.host(), qdrant.port(), qdrant.useTls())
                .withTimeout(qdrant.connectTimeout())
                .build();
        return new QdrantClient(grpc);
    }

    private static void closeQuietly(QdrantClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (RuntimeException e) {
            log.debug("Qdrant client close failed (ignored): {}", e.toString());
        }
    }
}
