package com.cy.diakritis.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ItemResult(
        String itemId,
        Verdict decision,
        List<Signal> signals
) {
}
