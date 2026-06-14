package com.cy.diakritis.engine.signal;

import com.cy.diakritis.common.dto.Counterparty;

/**
 * Resolves the canonical counterparty key used to look up baselines and accumulate the rolling
 * window. The key is the resolved account reference when the rail has resolved one (the strongest
 * identity), otherwise the addressing scheme and raw value joined with a pipe.
 */
public final class Identity {

    private Identity() {
    }

    /** Counterparty key: {@code resolvedAccountRef} if present, else {@code addressing + "|" + value}. */
    public static String counterpartyKey(Counterparty cp) {
        if (cp == null) {
            return "UNKNOWN";
        }
        String ref = cp.resolvedAccountRef();
        if (ref != null && !ref.isBlank()) {
            return ref;
        }
        String addressing = cp.addressing() == null ? "UNKNOWN" : cp.addressing().name();
        String value = cp.value() == null ? "" : cp.value();
        return addressing + "|" + value;
    }

    /**
     * Lower-cased, whitespace-collapsed name used as the Confirmation-of-Payee lookup key.
     *
     * <p>Case folding is pinned to {@link java.util.Locale#ROOT} so the key is identical regardless of
     * the JVM default locale. Without this, a Turkish ({@code tr-TR}) locale folds {@code 'I'} to the
     * dotless {@code 'ı'} rather than {@code 'i'}, so a name written under one locale (e.g. the ETL
     * seed on a US host) would not match the same name normalized at serve time under another — silently
     * defeating B5 (name mismatch) and the Ty2 invoice-redirection typology.
     */
    public static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }
}
