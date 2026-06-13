package com.cy.diakritis.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
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
        @NotNull @Positive BigDecimal totalEur,
        @NotNull @PositiveOrZero BigDecimal availableBalanceEur,
        @NotNull Rail rail
) implements ActionPayload {
}
