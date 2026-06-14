package com.cy.diakritis.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DepositBreakPayload(
        @NotBlank String depositId,
        // Bound the magnitude so an absurd principal is a 4xx (CI-8), not a cents-overflow 500.
        @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal principalEur,
        @NotNull Instant maturityDate,
        @PositiveOrZero @Digits(integer = 15, fraction = 2) BigDecimal penaltyEur
) implements ActionPayload {
}
