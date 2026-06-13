package com.cy.demobank.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/** A single line of a MASS_PAYMENT: {@code {item_id, counterparty, amount_eur}}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchItemDto(
        String itemId,
        CounterpartyDto counterparty,
        BigDecimal amountEur
) {
}
