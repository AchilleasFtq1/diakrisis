package com.cy.demobank.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

/** Request body for {@code POST /api/batches}: a multi-line mass payment on behalf of {@code customer}
 * (the account owner). */
public record BatchApiRequest(
        @NotBlank String customer,
        @NotBlank String account,
        @NotEmpty @Valid List<Line> lines,
        String rail
) {
    public String railOrDefault() {
        return rail == null || rail.isBlank() ? "SEPA" : rail;
    }

    public record Line(
            @NotBlank String itemId,
            @NotBlank String iban,
            String resolvedName,
            @NotNull @Positive @Digits(integer = 19, fraction = 2) BigDecimal amount
    ) {
    }
}
