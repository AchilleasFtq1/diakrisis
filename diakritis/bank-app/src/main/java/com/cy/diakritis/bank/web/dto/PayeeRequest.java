package com.cy.diakritis.bank.web.dto;

import com.cy.diakritis.common.dto.Addressing;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * A new beneficiary posted to {@code /payees}. Adding a payee is itself a scored action
 * ({@code BENEFICIARY_ADD}); the bank also persists the payee so later transfers can enrich from it.
 */
public record PayeeRequest(
        @NotNull Addressing addressing,
        @NotBlank String value,
        String resolvedAccountRef,
        String resolvedName,
        String displayName,
        @NotNull @Valid SessionRequest session
) {
}
