package com.cy.demobank.web.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Request body for {@code POST /api/transfer}: transfer from {@code account} to an existing
 * {@code payee} (its cp_key) on behalf of {@code customer} (the account owner). {@code rail} defaults
 * to SEPA when omitted. The {@code customer} identity is required so the unauthenticated REST surface
 * can only act on accounts whose owner the caller correctly names (ownership is asserted server-side).
 */
public record TransferApiRequest(
        @NotBlank String customer,
        @NotBlank String account,
        @NotBlank String payee,
        @NotNull @Positive @Digits(integer = 19, fraction = 2) BigDecimal amount,
        String rail
) {
    public String railOrDefault() {
        return rail == null || rail.isBlank() ? "SEPA" : rail;
    }
}
