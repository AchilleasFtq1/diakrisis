package com.cy.diakritis.bank.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Request to change a payment limit, posted to {@code /limits/change}.
 */
public record LimitChangeRequest(
        @NotNull @Positive BigDecimal currentLimitEur,
        @NotNull @Positive BigDecimal newLimitEur,
        @NotNull @Valid SessionRequest session
) {
}
