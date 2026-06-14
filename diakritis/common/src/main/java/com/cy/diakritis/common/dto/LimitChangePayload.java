package com.cy.diakritis.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LimitChangePayload(
        // Bound the magnitude so an absurd limit is a 4xx (CI-8), not a cents-overflow 500.
        @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal currentLimitEur,
        @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal newLimitEur
) implements ActionPayload {
}
