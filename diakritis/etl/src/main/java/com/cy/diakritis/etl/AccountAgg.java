package com.cy.diakritis.etl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mutable per-account accumulator for the outgoing (ROB) payment distribution. Keeps the raw
 * cent amounts so robust statistics (median, MAD) can be finalized after the full stream is read.
 */
final class AccountAgg {

    private final String accountId;
    private final Welford amounts = new Welford();
    private final List<Long> outgoingCents = new ArrayList<>();

    AccountAgg(String accountId) {
        this.accountId = accountId;
    }

    void add(long amountCents) {
        amounts.add(amountCents);
        outgoingCents.add(amountCents);
    }

    String accountId() {
        return accountId;
    }

    long outTxnCount() {
        return amounts.count();
    }

    long outMeanAmountCents() {
        return amounts.meanRounded();
    }

    long outStdAmountCents() {
        return amounts.stdRounded();
    }

    long outMedianAmountCents() {
        return median(sortedCopy());
    }

    /**
     * Median absolute deviation about the median, the robust-z denominator the engine consumes.
     * Returned in euro-cents.
     */
    long outMadAmountCents() {
        List<Long> sorted = sortedCopy();
        if (sorted.isEmpty()) {
            return 0L;
        }
        long median = median(sorted);
        List<Long> deviations = new ArrayList<>(sorted.size());
        for (long v : sorted) {
            deviations.add(Math.abs(v - median));
        }
        Collections.sort(deviations);
        return median(deviations);
    }

    private List<Long> sortedCopy() {
        List<Long> sorted = new ArrayList<>(outgoingCents);
        Collections.sort(sorted);
        return sorted;
    }

    private static long median(List<Long> sorted) {
        int n = sorted.size();
        if (n == 0) {
            return 0L;
        }
        int mid = n / 2;
        if (n % 2 == 1) {
            return sorted.get(mid);
        }
        return Math.round((sorted.get(mid - 1) + sorted.get(mid)) / 2.0);
    }
}
