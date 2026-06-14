package com.cy.demobank.web.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** Request body for {@code POST /api/p2p}: a P2P send to a phone-number alias on behalf of
 * {@code customer} (the account owner). */
public record P2pApiRequest(
        @NotBlank String customer,
        @NotBlank String account,
        @NotBlank String alias,
        @NotBlank String resolvedName,
        @NotNull @Positive @Digits(integer = 19, fraction = 2) BigDecimal amount
) {
}
