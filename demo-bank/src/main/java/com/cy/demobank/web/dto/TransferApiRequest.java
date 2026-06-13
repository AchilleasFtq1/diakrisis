package com.cy.demobank.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Request body for {@code POST /api/transfer}: transfer from {@code account} to an existing
 * {@code payee} (its cp_key). {@code rail} defaults to SEPA when omitted.
 */
public record TransferApiRequest(
        @NotBlank String account,
        @NotBlank String payee,
        @NotNull @Positive BigDecimal amount,
        String rail
) {
    public String railOrDefault() {
        return rail == null || rail.isBlank() ? "SEPA" : rail;
    }
}
