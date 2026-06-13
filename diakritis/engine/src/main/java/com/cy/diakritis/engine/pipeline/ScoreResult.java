package com.cy.diakritis.engine.pipeline;

import com.cy.diakritis.common.dto.EngineVerdict;
import com.cy.diakritis.common.dto.Explanation;
import com.cy.diakritis.common.dto.ItemResult;

import java.util.List;

/**
 * The complete output of the rules+model scoring pass for one action: the engine verdict, the
 * machine reason code, the dual-audience explanation, and (for mass payments) the per-item results.
 *
 * <p>This is the engine's hand-off to the orchestration tier, which adds the AI co-judge combine,
 * lifecycle and persistence. The reason code lives here (not on {@link EngineVerdict}, whose shape
 * is fixed by the common contract) so the controller can stamp it onto both the engine and combined
 * layers of the response.
 */
public record ScoreResult(
        EngineVerdict engineVerdict,
        String reasonCode,
        Explanation explanation,
        List<ItemResult> items
) {
    public ScoreResult {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
