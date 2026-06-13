package com.cy.demobank.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Request body for {@code POST /api/transfer-new}: drain to a brand-new IBAN (not an existing payee).
 * Used as the kill-chain's drain leg after {@code POST /api/deposits/{id}/break}.
 */
public record TransferToNewApiRequest(
        @NotBlank String account,
        @NotBlank String iban,
        String resolvedName,
        @NotNull @Positive BigDecimal amount,
        String rail
) {
    public String railOrDefault() {
        return rail == null || rail.isBlank() ? "SEPA" : rail;
    }
}
