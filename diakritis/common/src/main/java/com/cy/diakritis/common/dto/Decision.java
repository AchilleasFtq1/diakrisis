package com.cy.diakritis.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Decision(
        String eventId,
        EngineVerdict engineVerdict,
        AiCoJudge aiCoJudge,
        Combined combined,
        Lifecycle lifecycle,
        Explanation explanation,
        List<ItemResult> items,
        String reasonCode,
        long latencyMs
) {
}
