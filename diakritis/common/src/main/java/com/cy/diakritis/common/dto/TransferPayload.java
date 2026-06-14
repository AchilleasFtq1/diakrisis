package com.cy.diakritis.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransferPayload(
        @NotNull @Valid Counterparty counterparty,
        // @Digits bounds the magnitude so an absurd amount is a 4xx (CI-8) rather than overflowing the
        // cents conversion (longValueExact) deep in scoring and surfacing as a 500. 15 integer digits is
        // far above any real payment yet keeps cents well within a long.
        @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amountEur,
        @NotNull @PositiveOrZero @Digits(integer = 15, fraction = 2) BigDecimal availableBalanceEur,
        @NotNull Rail rail
) implements ActionPayload {
}
