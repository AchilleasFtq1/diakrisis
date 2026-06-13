package com.cy.diakritis.engine.pipeline;

import com.cy.diakritis.common.dto.Agreement;
import com.cy.diakritis.common.dto.Combined;
import com.cy.diakritis.common.dto.EngineVerdict;
import com.cy.diakritis.common.dto.Verdict;
import com.cy.diakritis.engine.band.Weights;
import com.cy.diakritis.engine.judge.AiCoJudge;

/**
 * Reconciles the engine verdict with the AI co-judge's opinion into the final {@link Combined}
 * decision.
 *
 * <p>The engine is authoritative by default; the co-judge can only ever make the outcome
 * <em>stricter</em>, never softer, and only when it is both confident (score ≥
 * {@link Weights#AI_ESCALATION_THRESHOLD}) and diverges in the stricter direction. A missing,
 * concurring or softer opinion leaves the engine decision untouched. When the co-judge wants to be
 * stricter but is below the confidence threshold, the engine decision stands but a review flag is
 * raised for human attention.
 */
public final class CombineRule {

    /** Basis string when the engine decision is taken as-is. */
    public static final String BASIS_ENGINE = "ENGINE";
    /** Basis string when the co-judge escalated the engine decision one band. */
    public static final String BASIS_ESCALATED = "AI_ESCALATED";
    /** Review-flag value when a sub-threshold stricter divergence is recorded. */
    public static final String REVIEW_DIVERGENCE = "AI_DIVERGENCE";

    /**
     * Combine the engine verdict with the co-judge opinion. {@code reasonCode} is the engine's own
     * reason code, propagated onto the combined result.
     */
    public Combined combine(EngineVerdict engine, AiCoJudge.Opinion ai, String reasonCode) {
        Verdict engineDecision = engine.decision();

        // No usable opinion (null / unavailable) → engine stands.
        if (ai == null || ai.isUnavailable() || ai.agreement() == null) {
            return new Combined(engineDecision, BASIS_ENGINE, reasonCode, null);
        }

        Agreement agreement = ai.agreement();

        // Concurring or softer divergence → engine stands.
        if (agreement == Agreement.CONCUR || agreement == Agreement.DIVERGE_SOFTER) {
            return new Combined(engineDecision, BASIS_ENGINE, reasonCode, null);
        }

        // Stricter divergence: escalate one band (capped at HOLD) only when the co-judge is confident.
        int aiScore = ai.score() == null ? 0 : ai.score();
        if (aiScore >= Weights.AI_ESCALATION_THRESHOLD) {
            Verdict escalated = escalateOneBand(engineDecision);
            return new Combined(escalated, BASIS_ESCALATED, reasonCode, null);
        }

        // Stricter but not confident → keep engine decision, flag for review.
        return new Combined(engineDecision, BASIS_ENGINE, reasonCode, REVIEW_DIVERGENCE);
    }

    /**
     * Escalate one band, capped at HOLD: ALLOW→CONFIRM, CONFIRM→HOLD, everything else unchanged.
     * REQUIRE_APPROVAL and BLOCK are already at or beyond HOLD severity and are left as-is.
     */
    private static Verdict escalateOneBand(Verdict decision) {
        return switch (decision) {
            case ALLOW -> Verdict.CONFIRM;
            case CONFIRM -> Verdict.HOLD;
            case HOLD, BLOCK, REQUIRE_APPROVAL -> decision;
        };
    }
}
