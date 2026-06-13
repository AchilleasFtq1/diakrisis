package com.cy.demobank.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * TERM_DEPOSIT_BREAK payload: {@code {deposit_id, principal_eur, maturity_date, penalty_eur}}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DepositBreakPayloadDto(
        String depositId,
        BigDecimal principalEur,
        Instant maturityDate,
        BigDecimal penaltyEur
) {
}
