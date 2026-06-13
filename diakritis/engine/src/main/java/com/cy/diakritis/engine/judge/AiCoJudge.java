package com.cy.diakritis.engine.judge;

import com.cy.diakritis.common.dto.ActionEvent;
import com.cy.diakritis.common.dto.Agreement;
import com.cy.diakritis.common.dto.EngineVerdict;
import com.cy.diakritis.common.dto.Verdict;

/**
 * A second, independent opinion on an action — the "AI co-judge" seam. Implementations may call an
 * external model; the engine combines this opinion with its own verdict via the combine rule.
 *
 * <p>The combine logic treats an {@link Opinion} whose {@code status} is non-{@code AVAILABLE} (or a
 * null opinion) as "no opinion", so a co-judge that is down never blocks a decision.
 */
public interface AiCoJudge {

    /** Status marker for an opinion produced by a live, reachable co-judge. */
    String STATUS_AVAILABLE = "AVAILABLE";
    /** Status marker for the resilience default when no co-judge is reachable. */
    String STATUS_UNAVAILABLE = "UNAVAILABLE";

    /**
     * The co-judge's opinion on an action and the engine's verdict for it.
     *
     * @param score     0-100 risk score (null when unavailable)
     * @param decision  the co-judge's own verdict (null when unavailable)
     * @param reason    short human-readable rationale
     * @param agreement how the opinion relates to the engine verdict
     * @param status    {@link #STATUS_AVAILABLE} or {@link #STATUS_UNAVAILABLE}
     */
    record Opinion(Integer score, Verdict decision, String reason, Agreement agreement, String status) {

        /** The canonical "no co-judge reachable" opinion. */
        public static Opinion unavailable() {
            return new Opinion(null, null, "AI co-judge unavailable", null, STATUS_UNAVAILABLE);
        }

        /** True when this opinion carries no usable judgement and must be ignored on combine. */
        public boolean isUnavailable() {
            return status == null || !STATUS_AVAILABLE.equals(status);
        }
    }

    /** Produce an opinion on {@code event} given the engine's {@code verdict}. */
    Opinion opine(ActionEvent event, EngineVerdict verdict);
}
