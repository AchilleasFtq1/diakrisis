package com.cy.diakritis.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LimitChangePayload(
        @NotNull @Positive BigDecimal currentLimitEur,
        @NotNull @Positive BigDecimal newLimitEur
) implements ActionPayload {
}
