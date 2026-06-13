package com.cy.diakritis.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record HoldInfo(
        int durationMinutes,
        Instant expiresAt,
        String cancelEndpoint,
        String releaseEndpoint
) {
}
