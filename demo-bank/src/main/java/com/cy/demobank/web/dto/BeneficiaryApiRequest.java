package com.cy.demobank.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /api/beneficiaries}: add a new payee (scored as BENEFICIARY_ADD). */
public record BeneficiaryApiRequest(
        @NotBlank String account,
        @NotBlank String iban,
        @NotBlank String displayName,
        String resolvedName
) {
}
