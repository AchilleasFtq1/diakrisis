package com.cy.diakritis.etl;

import com.cy.diakritis.common.persistence.RecentPayment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Mutable per-(account, counterparty) accumulator built while streaming the Berka transaction
 * log. Holds the running statistics plus the raw points needed to emit the most-recent payments.
 */
final class CounterpartyAgg {

    private static final int RECENT_LIMIT = 6;

    private final String accountId;
    private final String counterpartyKey;
    private final String counterpartyBank;
    private final String counterpartyAccount;
    private final Welford amounts = new Welford();
    private final List<RecentPayment> points = new ArrayList<>();

    private long firstSeenEpochMs = Long.MAX_VALUE;
    private long lastSeenEpochMs = Long.MIN_VALUE;

    CounterpartyAgg(String accountId, String counterpartyKey, String counterpartyBank, String counterpartyAccount) {
        this.accountId = accountId;
        this.counterpartyKey = counterpartyKey;
        this.counterpartyBank = counterpartyBank;
        this.counterpartyAccount = counterpartyAccount;
    }

    void add(long amountCents, long epochMs) {
        amounts.add(amountCents);
        points.add(new RecentPayment(amountCents, epochMs));
        if (epochMs < firstSeenEpochMs) {
            firstSeenEpochMs = epochMs;
        }
        if (epochMs > lastSeenEpochMs) {
            lastSeenEpochMs = epochMs;
        }
    }

    String accountId() {
        return accountId;
    }

    String counterpartyKey() {
        return counterpartyKey;
    }

    String counterpartyAccount() {
        return counterpartyAccount;
    }

    String counterpartyBank() {
        return counterpartyBank;
    }

    long payCount() {
        return amounts.count();
    }

    long meanAmountCents() {
        return amounts.meanRounded();
    }

    long stdAmountCents() {
        return amounts.stdRounded();
    }

    long firstSeenEpochMs() {
        return firstSeenEpochMs == Long.MAX_VALUE ? 0L : firstSeenEpochMs;
    }

    long lastSeenEpochMs() {
        return lastSeenEpochMs == Long.MIN_VALUE ? 0L : lastSeenEpochMs;
    }

    /**
     * Standing-order heuristic: a long run of identical amounts to one counterparty is, in the
     * Berka data, a recurring household payment (rent, insurance) rather than an ad-hoc transfer.
     */
    boolean isStandingOrder() {
        if (points.size() < 6) {
            return false;
        }
        long first = points.get(0).getAmountCents();
        for (RecentPayment p : points) {
            if (p.getAmountCents() != first) {
                return false;
            }
        }
        return true;
    }

    /** The {@value #RECENT_LIMIT} most recent payments, ascending by time. */
    List<RecentPayment> recentPayments() {
        List<RecentPayment> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparingLong(RecentPayment::getEpochMs));
        int from = Math.max(0, sorted.size() - RECENT_LIMIT);
        return new ArrayList<>(sorted.subList(from, sorted.size()));
    }
}
