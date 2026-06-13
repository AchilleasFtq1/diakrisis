package com.cy.demobank.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * TRANSFER / P2P_TRANSFER payload:
 * {@code {counterparty, amount_eur, available_balance_eur, rail}}. {@code rail} is SEPA, INSTANT,
 * INTERNAL or P2P.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransferPayloadDto(
        CounterpartyDto counterparty,
        BigDecimal amountEur,
        BigDecimal availableBalanceEur,
        String rail
) {
}
