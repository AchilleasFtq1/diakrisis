package com.cy.diakritis.bank.web.dto;

import com.cy.diakritis.common.dto.Addressing;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * One line of a mass payment batch.
 */
public record BatchItemRequest(
        @NotBlank String itemId,
        @NotNull Addressing addressing,
        @NotBlank String value,
        String resolvedAccountRef,
        @NotNull @Positive BigDecimal amountEur
) {
}
