package com.cy.demobank.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** BENEFICIARY_ADD payload: {@code {counterparty}}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BeneficiaryAddPayloadDto(
        CounterpartyDto counterparty
) {
}
