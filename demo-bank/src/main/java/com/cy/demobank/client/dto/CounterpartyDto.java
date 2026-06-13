package com.cy.demobank.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * The transfer/P2P/beneficiary counterparty:
 * {@code {addressing, value, resolved_account_ref, resolved_name, display_name, beneficiary_created_at}}.
 * {@code addressing} is IBAN, ACCOUNT, MSISDN or EMAIL.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CounterpartyDto(
        String addressing,
        String value,
        String resolvedAccountRef,
        String resolvedName,
        String displayName,
        Instant beneficiaryCreatedAt
) {
}
