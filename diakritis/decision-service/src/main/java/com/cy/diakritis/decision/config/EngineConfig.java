package com.cy.diakritis.decision.config;

import com.cy.diakritis.decision.EngineProperties;
import com.cy.diakritis.engine.judge.AiCoJudge;
import com.cy.diakritis.engine.judge.UnavailableAiCoJudge;
import com.cy.diakritis.engine.m1.M1Scorer;
import com.cy.diakritis.engine.m2.M2Scorer;
import com.cy.diakritis.engine.pipeline.CombineRule;
import com.cy.diakritis.engine.pipeline.ScoreEngine;
import com.cy.diakritis.engine.store.CidrGeoResolver;
import com.cy.diakritis.engine.store.GeoResolver;
import com.cy.diakritis.engine.store.RuntimeState;
import com.cy.diakritis.engine.typology.TypologyEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

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

    @Bean
    M2Scorer m2Scorer(EngineProperties properties) {
        Path modelsDir = Path.of(properties.getModelsDir());
        M2Scorer scorer = M2Scorer.load(modelsDir);
        LOG.info("M2Scorer constructed from models-dir={} (loaded={})", modelsDir, scorer.isLoaded());
        return scorer;
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

    @Bean
    AiCoJudge aiCoJudge() {
        return new UnavailableAiCoJudge();
    }

    @Bean
    CombineRule combineRule() {
        return new CombineRule();
    }
}
