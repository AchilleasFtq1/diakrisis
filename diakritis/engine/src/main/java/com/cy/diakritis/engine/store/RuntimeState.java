package com.cy.diakritis.engine.store;

import com.cy.diakritis.engine.band.Weights;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-process, in-memory rolling state that the offline feature store cannot supply: the
 * 24h "logical amount" window per {@code (accountId, cpKey)} that defends against salami /
 * structuring, plus per-session beneficiary-add / raised-amount timestamps for the B3 and C3 signals.
 *
 * <p>This is authoritative only for the current process lifetime; it is intentionally not
 * persisted. Money is integer euro-cents and time is epoch-millis throughout. The class is
 * thread-safe (a single lock guards every map), which matters under Spring's virtual-thread
 * request model.
 *
 * <p><b>Exactly-once recording (CI-1).</b> {@link #record} is idempotent per {@code eventId}: a
 * replayed or lost-idempotency-race request that re-records the same event does not double-count the
 * 24h window or the velocity deque. The first record for an {@code eventId} on a given pair wins; any
 * later record for that same {@code eventId} is a no-op. This is what makes a concurrent-duplicate
 * request safe even though it scores before the durable {@code putIfAbsent} commits.
 *
 * <p><b>Bounded growth.</b> Every map is pruned to its window on each write and read: once a deque
 * drains it is removed from its outer map, so the maps never accumulate empty entries for
 * pairs/accounts/sessions that have aged out. Recorded-event ids are dropped with their payment once
 * it leaves the 24h window.
 */
public final class RuntimeState {

    private static final long WINDOW_MS = Weights.LOGICAL_AMOUNT_WINDOW_HOURS * 60L * 60L * 1000L;

    private record Payment(String recordKey, long amountCents, long epochMs) {
    }

    private static final long HOUR_MS = 60L * 60L * 1000L;
    /** Velocity-burst window: how far back V1 counts an account's actions. */
    private static final long VELOCITY_WINDOW_MS =
            Weights.POSTURE_VELOCITY_WINDOW_HOURS * 60L * 60L * 1000L;
    /** Beneficiary-add (B3) / session-posture window for the per-session add timestamps. */
    private static final long BENEFICIARY_ADD_WINDOW_MS =
            Weights.POSTURE_BENEFICIARY_ADD_WINDOW_HOURS * 60L * 60L * 1000L;
    /** C3 retry-pressure window: probing is a now-condition, so only recent raises count. */
    private static final long RAISED_ATTEMPT_WINDOW_MS =
            Weights.C3_RETRY_WINDOW_MINUTES * 60L * 1000L;

    /**
     * Unit-separator (US, {@code U+001F}) delimiter for the rolling-window pair key. A control
     * character that cannot appear in a real accountId or counterparty value, so two distinct
     * {@code (accountId, cpKey)} pairs can never collide into the same window even when the ids contain
     * spaces (the rest of the engine keys counterparties with a structural delimiter for the same
     * reason). The previous space separator could merge unrelated pairs and under-/over-count the
     * salami window.
     */
    private static final char PAIR_KEY_SEPARATOR = '\u001F';

    private final Map<String, Deque<Payment>> windowByPair = new HashMap<>();
    private final Map<String, Deque<Long>> beneficiaryAddsBySession = new HashMap<>();
    private final Map<String, Deque<Long>> actionsByAccount = new HashMap<>();
    private final Map<String, Deque<RaisedAttempt>> raisedAttemptsBySession = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    private record RaisedAttempt(long amountCents, long epochMs) {
    }

    /**
     * Record an outgoing payment of {@code amountCents} to {@code cpKey} at {@code nowEpochMs} for the
     * event/line being scored, so {@link #logicalAmountCents} includes the current amount in its window
     * sum.
     *
     * <p>Idempotent per {@code recordKey}: if this {@code (pair, recordKey)} has already been recorded
     * (a replay, or the losing side of a concurrent same-event race), the call is a no-op for both the
     * 24h window and the velocity deque, so neither is double-counted. The {@code recordKey} must be
     * stable across replays yet unique per logical payment — {@code eventId} for a single transfer,
     * {@code eventId#itemId} for a mass-payment line, so distinct lines of one batch each count while a
     * replay of the whole batch does not. A {@code null} {@code recordKey} (test/legacy callers) always
     * records, preserving the previous additive behaviour.
     */
    public void record(String recordKey, String accountId, String cpKey, long amountCents, long nowEpochMs) {
        String key = pairKey(accountId, cpKey);
        lock.lock();
        try {
            Deque<Payment> window = windowByPair.computeIfAbsent(key, k -> new ArrayDeque<>());
            evictOlderThan(window, nowEpochMs - WINDOW_MS);

            boolean firstRecord = recordKey == null || !containsRecord(window, recordKey);
            if (firstRecord) {
                window.addLast(new Payment(recordKey, amountCents, nowEpochMs));
            }
            if (window.isEmpty()) {
                windowByPair.remove(key);
            }

            // Keep the velocity deque in lock-step with the window: only a first record for this
            // payment contributes a velocity tick, so a duplicate never inflates V1's actions-per-hour.
            Deque<Long> actions = actionsByAccount.computeIfAbsent(accountId, k -> new ArrayDeque<>());
            if (firstRecord) {
                actions.addLast(nowEpochMs);
            }
            evictLongsOlderThan(actions, nowEpochMs - VELOCITY_WINDOW_MS);
            if (actions.isEmpty()) {
                actionsByAccount.remove(accountId);
            }
        } finally {
            lock.unlock();
        }
    }

    private static boolean containsRecord(Deque<Payment> window, String recordKey) {
        for (Payment p : window) {
            if (recordKey.equals(p.recordKey())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Actions per hour for this account over the velocity window, including the just-recorded event.
     * Drives V1 (burst velocity): a normal account makes well under one payment an hour; a
     * coached-scam or mule burst spikes to many. Computed as {@code count / windowHours}, with a
     * one-hour floor on the window so a freshly-started process does not over-report.
     */
    public double actionsPerHour(String accountId, long nowEpochMs) {
        lock.lock();
        try {
            Deque<Long> actions = actionsByAccount.get(accountId);
            if (actions == null || actions.isEmpty()) {
                return 0.0;
            }
            evictLongsOlderThan(actions, nowEpochMs - VELOCITY_WINDOW_MS);
            if (actions.isEmpty()) {
                actionsByAccount.remove(accountId);
                return 0.0;
            }
            int count = actions.size();
            long oldest = actions.peekFirst();
            long spanMs = Math.max(HOUR_MS, nowEpochMs - oldest);
            double spanHours = (double) spanMs / (double) HOUR_MS;
            return count / spanHours;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Record a server-side raised-amount retry attempt within {@code sessionId}: each time the same
     * session re-submits at a higher amount it is a sign of someone pushing against a limit/control.
     * Drives C3 (retry pressure). The session's attempts are pruned to the C3 retry window on every
     * write so the deque (and the outer map) stay bounded to active, recent sessions.
     */
    public void recordRaisedAttempt(String sessionId, long amountCents, long nowEpochMs) {
        if (sessionId == null) {
            return;
        }
        lock.lock();
        try {
            Deque<RaisedAttempt> attempts =
                    raisedAttemptsBySession.computeIfAbsent(sessionId, k -> new ArrayDeque<>());
            attempts.addLast(new RaisedAttempt(amountCents, nowEpochMs));
            evictRaisedOlderThan(attempts, nowEpochMs - RAISED_ATTEMPT_WINDOW_MS);
            if (attempts.isEmpty()) {
                raisedAttemptsBySession.remove(sessionId);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Number of times this session raised the amount over its previous attempts within the C3 retry
     * window (the count of strictly-increasing steps among the in-window attempts). 0 when there are
     * fewer than two in-window attempts or amounts never rose. Stale attempts outside the window are
     * pruned here, and a session whose attempts have all aged out is removed from the map, so a
     * long-lived session that occasionally raises an amount no longer saturates C3 over its lifetime.
     */
    public int raisedAttemptCount(String sessionId, long nowEpochMs) {
        if (sessionId == null) {
            return 0;
        }
        lock.lock();
        try {
            Deque<RaisedAttempt> attempts = raisedAttemptsBySession.get(sessionId);
            if (attempts == null) {
                return 0;
            }
            evictRaisedOlderThan(attempts, nowEpochMs - RAISED_ATTEMPT_WINDOW_MS);
            if (attempts.isEmpty()) {
                raisedAttemptsBySession.remove(sessionId);
                return 0;
            }
            if (attempts.size() < 2) {
                return 0;
            }
            int raises = 0;
            long previous = Long.MIN_VALUE;
            for (RaisedAttempt attempt : attempts) {
                if (previous != Long.MIN_VALUE && attempt.amountCents() > previous) {
                    raises++;
                }
                previous = attempt.amountCents();
            }
            return raises;
        } finally {
            lock.unlock();
        }
    }

    /**
     * The logical amount for this counterparty: {@code max(thisAmount, Σ over last 24h)}.
     * Callers must {@link #record} the current event first; the current amount is therefore
     * already part of the window sum and {@code thisAmountCents} only guards the (impossible
     * after record) empty-window case and any clock skew.
     */
    public long logicalAmountCents(String accountId, String cpKey, long thisAmountCents, long nowEpochMs) {
        String key = pairKey(accountId, cpKey);
        lock.lock();
        try {
            Deque<Payment> window = windowByPair.get(key);
            if (window == null || window.isEmpty()) {
                return thisAmountCents;
            }
            evictOlderThan(window, nowEpochMs - WINDOW_MS);
            if (window.isEmpty()) {
                windowByPair.remove(key);
                return thisAmountCents;
            }
            long sum = 0L;
            for (Payment p : window) {
                sum += p.amountCents();
            }
            return Math.max(thisAmountCents, sum);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Record that a beneficiary was added within {@code sessionId} at {@code nowEpochMs}.
     * Used by B3 to detect a payee created moments before paying it in the same session. The
     * session's adds are pruned to the beneficiary-add window on every write so the deque (and the
     * outer map) stay bounded to active, recent sessions.
     */
    public void recordBeneficiaryAdd(String sessionId, long nowEpochMs) {
        if (sessionId == null) {
            return;
        }
        lock.lock();
        try {
            Deque<Long> adds = beneficiaryAddsBySession
                    .computeIfAbsent(sessionId, k -> new ArrayDeque<>());
            adds.addLast(nowEpochMs);
            evictLongsOlderThan(adds, nowEpochMs - BENEFICIARY_ADD_WINDOW_MS);
            if (adds.isEmpty()) {
                beneficiaryAddsBySession.remove(sessionId);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * True if any beneficiary was added in {@code sessionId} within {@code withinMs}
     * before {@code nowEpochMs}.
     */
    public boolean beneficiaryAddedInSession(String sessionId, long nowEpochMs, long withinMs) {
        if (sessionId == null) {
            return false;
        }
        lock.lock();
        try {
            Deque<Long> adds = beneficiaryAddsBySession.get(sessionId);
            if (adds == null || adds.isEmpty()) {
                return false;
            }
            // Prune anything beyond the bounded session window so a session that never gets another
            // write is still reclaimed on read; then bound the lookback to the caller's window.
            evictLongsOlderThan(adds, nowEpochMs - BENEFICIARY_ADD_WINDOW_MS);
            if (adds.isEmpty()) {
                beneficiaryAddsBySession.remove(sessionId);
                return false;
            }
            long floor = nowEpochMs - withinMs;
            for (Long ts : adds) {
                if (ts >= floor && ts <= nowEpochMs) {
                    return true;
                }
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    private static void evictOlderThan(Deque<Payment> window, long floorEpochMs) {
        while (!window.isEmpty() && window.peekFirst().epochMs() < floorEpochMs) {
            window.pollFirst();
        }
    }

    private static void evictLongsOlderThan(Deque<Long> window, long floorEpochMs) {
        while (!window.isEmpty() && window.peekFirst() < floorEpochMs) {
            window.pollFirst();
        }
    }

    private static void evictRaisedOlderThan(Deque<RaisedAttempt> attempts, long floorEpochMs) {
        while (!attempts.isEmpty() && attempts.peekFirst().epochMs() < floorEpochMs) {
            attempts.pollFirst();
        }
    }

    private static String pairKey(String accountId, String cpKey) {
        return accountId + PAIR_KEY_SEPARATOR + cpKey;
    }
}
