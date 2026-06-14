package com.cy.demobank.web.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Request body for {@code POST /api/transfer-new}: drain to a brand-new IBAN (not an existing payee)
 * on behalf of {@code customer} (the account owner). Used as the kill-chain's drain leg after
 * {@code POST /api/deposits/{id}/break}.
 */
public record TransferToNewApiRequest(
        @NotBlank String customer,
        @NotBlank String account,
        @NotBlank String iban,
        String resolvedName,
        @NotNull @Positive @Digits(integer = 19, fraction = 2) BigDecimal amount,
        String rail
) {
    public String railOrDefault() {
        return rail == null || rail.isBlank() ? "SEPA" : rail;
    }
}
