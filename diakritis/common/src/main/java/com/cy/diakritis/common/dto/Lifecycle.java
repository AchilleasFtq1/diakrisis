package com.cy.diakritis.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Lifecycle(
        LifecycleState state,
        Boolean executed,
        HoldInfo hold,
        ApprovalInfo approval
) {
}
