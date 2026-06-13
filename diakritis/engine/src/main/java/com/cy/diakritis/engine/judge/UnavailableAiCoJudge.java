package com.cy.diakritis.engine.judge;

import com.cy.diakritis.common.dto.ActionEvent;
import com.cy.diakritis.common.dto.EngineVerdict;

/**
 * The resilience-default co-judge: always reports {@code UNAVAILABLE}. This is a real, deliberate
 * implementation (per the SDD resilience contract), not a stub — it is the production default when
 * no external model is wired in, and it guarantees the engine verdict is never altered by a missing
 * co-judge (the combine rule keeps the engine decision on an unavailable opinion).
 */
public final class UnavailableAiCoJudge implements AiCoJudge {

    @Override
    public Opinion opine(ActionEvent event, EngineVerdict verdict) {
        return Opinion.unavailable();
    }
}
