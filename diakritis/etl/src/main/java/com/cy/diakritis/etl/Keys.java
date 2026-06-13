package com.cy.diakritis.etl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * Shared key, money, and time helpers for the ETL. Keys mirror the {@code pk}/{@code sk} layout
 * documented on the {@code @DynamoDbBean} item classes in {@code common.persistence}.
 */
final class Keys {

    static final String SOURCE_BERKA = "BERKA";
    static final String SOURCE_CONSTRUCTED = "CONSTRUCTED";

    private Keys() {
    }

    static String accountPk(String accountId) {
        return "ACC#" + accountId;
    }

    static String counterpartySk(String counterpartyKey) {
        return "CP#" + counterpartyKey;
    }

    static String nameSk(String normalizedName) {
        return "NAME#" + normalizedName;
    }

    static String payeeSk(String counterpartyKey) {
        return "PAYEE#" + counterpartyKey;
    }

    static final String META_SK = "META";

    /** Berka {@code bank|account} counterparty key. */
    static String counterpartyKey(String bank, String counterpartyAccount) {
        return bank + "|" + counterpartyAccount;
    }

    /** Parse a fixed-2-decimal euro string (e.g. {@code "195.30"}) to integer euro-cents. */
    static long eurToCents(String euros) {
        return new BigDecimal(euros.trim())
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** Midnight UTC epoch-millis for a {@code yyyy-MM-dd} Berka date. */
    static long dateToEpochMs(String isoDate) {
        return LocalDate.parse(isoDate.trim())
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli();
    }

    /** Confirmation-of-Payee normalization: upper-case, collapse non-alphanumerics. */
    static String normalizeName(String displayName) {
        return displayName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim();
    }
}
