package com.cy.demobank.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * MASS_PAYMENT payload:
 * {@code {batch_id, purpose_hint, items, total_eur, available_balance_eur, rail}}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MassPaymentPayloadDto(
        String batchId,
        String purposeHint,
        List<BatchItemDto> items,
        BigDecimal totalEur,
        BigDecimal availableBalanceEur,
        String rail
) {
}
