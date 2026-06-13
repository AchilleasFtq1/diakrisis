package com.cy.diakritis.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Explanation(
        String customer,
        String audit
) {
}
