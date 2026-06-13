package com.cy.diakritis.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Counterparty(
        Addressing addressing,
        String value,
        String resolvedAccountRef,
        String resolvedName,
        String displayName,
        Instant beneficiaryCreatedAt
) {
}
