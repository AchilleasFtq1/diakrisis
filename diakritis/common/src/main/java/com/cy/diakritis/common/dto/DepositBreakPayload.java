package com.cy.diakritis.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DepositBreakPayload(
        @NotBlank String depositId,
        @NotNull @Positive BigDecimal principalEur,
        @NotNull Instant maturityDate,
        @PositiveOrZero BigDecimal penaltyEur
) implements ActionPayload {
}
