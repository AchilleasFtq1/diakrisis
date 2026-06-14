package com.cy.diakritis.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MassPaymentPayload(
        @NotBlank String batchId,
        String purposeHint,
        @NotEmpty @Valid List<BatchItem> items,
        // Bound the magnitude so an absurd batch total is a 4xx (CI-8), not a cents-overflow 500.
        @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal totalEur,
        @NotNull @PositiveOrZero @Digits(integer = 15, fraction = 2) BigDecimal availableBalanceEur,
        @NotNull Rail rail
) implements ActionPayload {
}
