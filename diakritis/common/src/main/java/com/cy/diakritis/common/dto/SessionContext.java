package com.cy.diakritis.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionContext(
        @NotNull Instant ts,
        @NotBlank String sessionId,
        @NotNull Channel channel,
        String ip,
        @NotNull @Valid DeviceInfo device
) {
}
