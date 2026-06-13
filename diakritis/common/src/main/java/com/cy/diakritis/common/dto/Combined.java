package com.cy.diakritis.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Combined(
        Verdict decision,
        String basis,
        String reasonCode,
        String reviewFlag
) {
}
