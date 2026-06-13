package com.cy.diakritis.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransferPayload(
        @NotNull @Valid Counterparty counterparty,
        @NotNull @Positive BigDecimal amountEur,
        @NotNull @PositiveOrZero BigDecimal availableBalanceEur,
        @NotNull Rail rail
) implements ActionPayload {
}
