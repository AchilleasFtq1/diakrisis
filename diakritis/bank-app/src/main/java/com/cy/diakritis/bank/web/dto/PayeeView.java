package com.cy.diakritis.bank.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Public view of a saved payee returned by {@code GET /payees}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PayeeView(
        String counterpartyKey,
        String iban,
        String displayName,
        String resolvedName,
        Instant createdAt,
        String source
) {
}
