package com.cy.diakritis.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchItem(
        @NotBlank String itemId,
        @NotNull @Valid Counterparty counterparty,
        @NotNull @Positive BigDecimal amountEur
) {
}
