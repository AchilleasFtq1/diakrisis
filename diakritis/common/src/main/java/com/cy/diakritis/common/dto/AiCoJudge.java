package com.cy.diakritis.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiCoJudge(
        Integer score,
        Verdict decision,
        String reason,
        Agreement agreement,
        String status
) {
}
