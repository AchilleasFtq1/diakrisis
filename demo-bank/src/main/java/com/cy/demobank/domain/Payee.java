package com.cy.demobank.domain;

/**
 * An established (or newly added) payee of a demo account. {@code cpKey} is the counterparty key
 * the Diakrisis engine reads its baseline under (e.g. {@code CD|46939146}); {@code iban} is the
 * addressing value carried on the wire as {@code counterparty.value}.
 */
public record Payee(
        String accountId,
        String cpKey,
        String iban,
        String bic,
        String displayName,
        String resolvedName,
        long createdEpochMs
) {
}
