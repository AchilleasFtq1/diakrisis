package com.cy.diakritis.decision.config;

import com.cy.diakritis.decision.EngineProperties;
import com.cy.diakritis.engine.judge.AiCoJudge;
import com.cy.diakritis.engine.judge.OllamaAiCoJudge;
import com.cy.diakritis.engine.judge.UnavailableAiCoJudge;
import com.cy.diakritis.engine.m1.M1Scorer;
import com.cy.diakritis.engine.m2.ExemplarIndex;
import com.cy.diakritis.engine.m2.M2Backend;
import com.cy.diakritis.engine.m2.M2Scaler;
import com.cy.diakritis.engine.m2.M2Scorer;
import com.cy.diakritis.engine.m2.QdrantExemplarIndexFactory;
import com.cy.diakritis.engine.m2.QdrantExemplarIndexFactory.QdrantConfig;
import io.qdrant.client.QdrantClient;
import com.cy.diakritis.engine.pipeline.CombineRule;
import com.cy.diakritis.engine.pipeline.ScoreEngine;
import com.cy.diakritis.engine.store.CidrGeoResolver;
import com.cy.diakritis.engine.store.GeoResolver;
import com.cy.diakritis.engine.store.RuntimeState;
import com.cy.diakritis.engine.typology.TypologyEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Constructs the scoring core as Spring singletons.
 *
 * <p>Wiring (contractual):
 * <ul>
 *   <li>{@link M1Scorer} loads the pre-trained GradientTreeBoost + isotonic + percentile artifacts
 *       from {@code diakrisis.models-dir} once at startup; a load failure degrades M1 to a constant-0
 *       signal without failing the context.</li>
 *   <li>{@link ScoreEngine} is built from that {@link M1Scorer} and a {@link TypologyEvaluator}; it
 *       owns the deterministic signal/band/typology pipeline.</li>
 *   <li>{@link RuntimeState} is a single process-wide bean holding the rolling 24h window and
 *       per-session beneficiary-add timestamps (thread-safe under virtual threads).</li>
 *   <li>{@link UnavailableAiCoJudge} is the resilience-default co-judge; {@link CombineRule} keeps
 *       the engine verdict whenever the co-judge is unavailable (combined == engine).</li>
 * </ul>
 */
@Configuration
public class EngineConfig {

    private static final Logger LOG = LoggerFactory.getLogger(EngineConfig.class);

    @Bean
    M1Scorer m1Scorer(EngineProperties properties) {
        Path modelsDir = Path.of(properties.getModelsDir());
        M1Scorer scorer = new M1Scorer(modelsDir);
        LOG.info("M1Scorer constructed from models-dir={} (loaded={})", modelsDir, scorer.isLoaded());
        return scorer;
    }

    /**
     * The §9.2 M2 backend resolution. {@code diakrisis.m2.backend} selects the {@link ExemplarIndex}:
     * {@code qdrant} / {@code auto} → live cosine k-NN over a running Qdrant (gRPC), with the in-JVM
     * Smile KDTree as the automatic fallback when Qdrant is unreachable or empty at boot; {@code kdtree}
     * → the KDTree directly without contacting Qdrant. The query vector and the exemplars share one
     * {@link M2Scaler} transform regardless of backend, so the two indexes return the same
     * distance-weighted fraud share on the same vector. The resolution is exposed as a bean so its
     * Qdrant client (when one is opened) is closed on shutdown via {@link #qdrantClientLifecycle}.
     */
    @Bean
    QdrantExemplarIndexFactory.Resolution m2Resolution(EngineProperties properties) {
        Path modelsDir = Path.of(properties.getModelsDir());
        EngineProperties.M2 m2 = properties.getM2();
        M2Backend requested = M2Backend.fromConfig(m2.getBackend());

        M2Scaler scaler = loadScaler(modelsDir);
        ExemplarIndex kdTree = loadKdTree(modelsDir, m2.getKdtreeTable(), scaler);

        EngineProperties.M2.Qdrant q = m2.getQdrant();
        QdrantConfig qdrantConfig = new QdrantConfig(
                q.getHost(), q.getPort(), q.isUseTls(), q.getCollection(),
                Duration.ofMillis(q.getConnectTimeoutMs()), Duration.ofMillis(q.getQueryTimeoutMs()));

        QdrantExemplarIndexFactory.Resolution resolution =
                QdrantExemplarIndexFactory.resolve(requested, scaler, kdTree, qdrantConfig);
        LOG.info("M2 backend resolved: requested={} effective={} qdrantPoints={} scorerLoaded={}",
                requested, resolution.effectiveBackend(), resolution.qdrantPointCount(),
                resolution.scorer().isLoaded());
        return resolution;
    }

    @Bean
    M2Scorer m2Scorer(QdrantExemplarIndexFactory.Resolution m2Resolution) {
        return m2Resolution.scorer();
    }

    /**
     * Closes the Qdrant gRPC client (if the live backend was wired) when the context shuts down, so
     * the gRPC channel + Netty event loop are released cleanly. A no-op when M2 resolved to KDTree.
     */
    @Bean
    DisposableBean qdrantClientLifecycle(QdrantExemplarIndexFactory.Resolution m2Resolution) {
        return () -> {
            QdrantClient client = m2Resolution.qdrantClient();
            if (client != null) {
                client.close();
                LOG.info("Qdrant M2 client closed.");
            }
        };
    }

    /** Load the M2 scaler; a missing/malformed file disables M2 (the empty-index resilience default). */
    private static M2Scaler loadScaler(Path modelsDir) {
        try {
            return M2Scaler.load(modelsDir);
        } catch (IOException | RuntimeException e) {
            LOG.warn("M2 scaler unavailable at {}; M2 disabled. cause={}", modelsDir, e.toString());
            return null;
        }
    }

    /**
     * Build the in-JVM KDTree fallback from the configured table ({@code diakrisis.m2.kdtree-table},
     * resolved against {@code models-dir} when relative). An absent table yields the empty index —
     * the §9.2 resilience default (M2 scores 0) the golden-path baseline is calibrated against.
     */
    private static ExemplarIndex loadKdTree(Path modelsDir, String kdtreeTable, M2Scaler scaler) {
        if (scaler == null) {
            return ExemplarIndex.empty();
        }
        Path table = Path.of(kdtreeTable);
        if (!table.isAbsolute()) {
            table = modelsDir.resolve(table);
        }
        try {
            return M2Scorer.loadKdTreeExemplars(table, scaler);
        } catch (IOException | RuntimeException e) {
            LOG.warn("M2 KDTree fallback table unavailable at {}; KDTree fallback empty. cause={}",
                    table, e.toString());
            return ExemplarIndex.empty();
        }
    }

    @Bean
    TypologyEvaluator typologyEvaluator() {
        return new TypologyEvaluator();
    }

    @Bean
    ScoreEngine scoreEngine(M1Scorer m1Scorer, M2Scorer m2Scorer, TypologyEvaluator typologyEvaluator) {
        return new ScoreEngine(m1Scorer, m2Scorer, typologyEvaluator);
    }

    @Bean
    RuntimeState runtimeState() {
        return new RuntimeState();
    }

    /**
     * The §5/§6 geo seam: a local longest-prefix-match CIDR→country resolver seeded from
     * {@code diakrisis.geo-cidrs} (Cyprus home + foreign Jordan ranges by default). It needs no
     * network or geolocation file, so G1/G2 are deterministic and self-contained.
     */
    @Bean
    GeoResolver geoResolver(EngineProperties properties) {
        CidrGeoResolver resolver = CidrGeoResolver.of(properties.getGeoCidrs());
        LOG.info("GeoResolver constructed from {} CIDR block(s)", properties.getGeoCidrs().size());
        return resolver;
    }

    /**
     * The AI co-judge bean (SDD §9.4): a live Ollama-hosted Gemma when configured and reachable, else
     * the resilience-default {@link UnavailableAiCoJudge}. Wiring is configurable via
     * {@code diakrisis.cojudge.enabled} + {@code diakrisis.cojudge.ollama-url}; the default is ON, and
     * it degrades to UNAVAILABLE automatically when Ollama is not reachable at boot, so the service
     * boots and scores identically whether or not a model daemon is present.
     *
     * <p>When live, the model is warmed up here (one untimed call) so it is resident before the first
     * budgeted decision. Reachability is probed against {@code /api/tags} with the same hard budget as
     * a real call, so an absent daemon never delays startup beyond the timeout.
     */
    @Bean
    AiCoJudge aiCoJudge(EngineProperties properties) {
        EngineProperties.CoJudge cfg = properties.getCojudge();
        if (!cfg.isEnabled()) {
            LOG.info("AI co-judge disabled by config (diakrisis.cojudge.enabled=false); using UNAVAILABLE default");
            return new UnavailableAiCoJudge();
        }
        if (!isOllamaReachable(cfg.getOllamaUrl(), cfg.getTimeoutMs())) {
            LOG.warn("AI co-judge enabled but Ollama not reachable at {}; using UNAVAILABLE default",
                    cfg.getOllamaUrl());
            return new UnavailableAiCoJudge();
        }
        OllamaAiCoJudge coJudge = new OllamaAiCoJudge(
                cfg.getOllamaUrl(), cfg.getModel(), Duration.ofMillis(cfg.getTimeoutMs()));
        if (cfg.isWarmUpOnBoot()) {
            coJudge.warmUp();
        }
        LOG.info("AI co-judge LIVE: model '{}' via Ollama at {} (budget {} ms)",
                cfg.getModel(), cfg.getOllamaUrl(), cfg.getTimeoutMs());
        return coJudge;
    }

    /**
     * Probe Ollama's {@code /api/tags} endpoint to decide whether to wire the live co-judge. A short
     * connect+read budget keeps boot fast when no daemon is present; any error (connection refused,
     * timeout, non-200) is treated as "not reachable" and falls back to UNAVAILABLE.
     */
    private static boolean isOllamaReachable(String ollamaUrl, long timeoutMs) {
        String base = ollamaUrl.endsWith("/") ? ollamaUrl.substring(0, ollamaUrl.length() - 1) : ollamaUrl;
        Duration budget = Duration.ofMillis(Math.max(timeoutMs, 250L));
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(budget)
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest
                    .newBuilder(java.net.URI.create(base + "/api/tags"))
                    .timeout(budget)
                    .GET()
                    .build();
            java.net.http.HttpResponse<Void> response =
                    client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException | java.io.IOException ex) {
            return false;
        }
    }

    @Bean
    CombineRule combineRule() {
        return new CombineRule();
    }

    /**
     * The hard per-decision co-judge budget (SDD §9.4 ≈600 ms). The engine never waits on the AI
     * beyond this; an overrun yields UNAVAILABLE and the engine decision stands. Exposed as a bean so
     * the orchestration tier and the co-judge share one source of truth.
     */
    @Bean
    CoJudgeBudget coJudgeBudget(EngineProperties properties) {
        return new CoJudgeBudget(Duration.ofMillis(properties.getCojudge().getTimeoutMs()));
    }

    /** Immutable holder for the co-judge hard time budget. */
    public record CoJudgeBudget(Duration budget) {
    }
}
