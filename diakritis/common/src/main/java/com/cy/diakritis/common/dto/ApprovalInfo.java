package com.cy.diakritis.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApprovalInfo(
        String reason,
        String approveEndpoint,
        String rejectEndpoint,
        int expiresInHours
) {
}
