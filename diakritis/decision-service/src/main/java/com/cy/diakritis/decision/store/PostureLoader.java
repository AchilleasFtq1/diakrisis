package com.cy.diakritis.decision.store;

import com.cy.diakritis.common.persistence.AccountPostureItem;
import com.cy.diakritis.decision.repo.AccountPostureRepository;
import com.cy.diakritis.engine.band.Weights;
import com.cy.diakritis.engine.store.PostureView;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Loads the engine's {@link PostureView} from the {@code AccountPosture} table.
 *
 * <p>The posture is rolling, but each kill-chain counter decays over its OWN horizon rather than all
 * three being gated on a single deposit-break timestamp:
 * <ul>
 *   <li><b>fundsFreed</b> (K1) is surfaced while the last deposit break is within the 168h funds-freed
 *       window ({@link Weights#POSTURE_FUNDS_FREED_WINDOW_HOURS}); K1 then applies its own exponential
 *       decay over {@code lastDepositBreakEpochMs}. (Previously the loader truncated this at 72h, making
 *       the documented 7-day K1 linkage dead code.)</li>
 *   <li><b>limitRaised</b> (K2) is surfaced while the last limit-raise is within the limit-raise window
 *       ({@link Weights#POSTURE_LIMIT_RAISED_WINDOW_HOURS}).</li>
 *   <li><b>beneficiaryAddCount</b> (K3) is surfaced while the last beneficiary-add is within the
 *       beneficiary-add window ({@link Weights#POSTURE_BENEFICIARY_ADD_WINDOW_HOURS}).</li>
 * </ul>
 *
 * <p>An absent record yields an empty posture anchored at {@code now} so a cold account scores cleanly.
 * A present record always surfaces each counter independently — a stale counter is zeroed on its own,
 * never because an unrelated counter (e.g. the deposit break) decayed. This is what lets K2 and K3 fire
 * for an account that raised its limit or burst-added payees WITHOUT also having a recent deposit break.
 */
@Component
public class PostureLoader {

    private static final long FUNDS_FREED_WINDOW_MS =
            Weights.POSTURE_FUNDS_FREED_WINDOW_HOURS * 60L * 60L * 1000L;
    private static final long LIMIT_RAISED_WINDOW_MS =
            Weights.POSTURE_LIMIT_RAISED_WINDOW_HOURS * 60L * 60L * 1000L;
    private static final long BENEFICIARY_ADD_WINDOW_MS =
            Weights.POSTURE_BENEFICIARY_ADD_WINDOW_HOURS * 60L * 60L * 1000L;

    private final AccountPostureRepository accountPostureRepository;

    public PostureLoader(AccountPostureRepository accountPostureRepository) {
        this.accountPostureRepository = accountPostureRepository;
    }

    public PostureView load(String accountId, Instant now) {
        Optional<AccountPostureItem> posture = accountPostureRepository.find(accountId);
        if (posture.isEmpty()) {
            return PostureView.empty(now.toEpochMilli());
        }
        AccountPostureItem item = posture.get();
        long nowMs = now.toEpochMilli();

        long lastBreak = item.getLastDepositBreakEpochMs();
        long freedFunds = withinWindow(lastBreak, nowMs, FUNDS_FREED_WINDOW_MS)
                ? item.getFundsFreedEur72hCents() : 0L;

        long limitRaiseActivity = activityTimestamp(item.getLastLimitRaiseEpochMs(), lastBreak);
        long limitRaised = withinWindow(limitRaiseActivity, nowMs, LIMIT_RAISED_WINDOW_MS)
                ? item.getLimitRaised72hCents() : 0L;

        long beneficiaryAddActivity = activityTimestamp(item.getLastBeneficiaryAddEpochMs(), lastBreak);
        long beneficiaryAddCount = withinWindow(beneficiaryAddActivity, nowMs, BENEFICIARY_ADD_WINDOW_MS)
                ? item.getBeneficiaryAddCount72h() : 0L;

        return new PostureView(freedFunds, limitRaised, beneficiaryAddCount, lastBreak);
    }

    /**
     * The activity timestamp to window a counter against: its own per-counter timestamp when present,
     * else a fallback to {@code lastDepositBreakEpochMs} for records written before per-counter
     * timestamps existed (so legacy rows still surface their counters within the deposit-break window).
     */
    private static long activityTimestamp(long counterTimestamp, long fallbackTimestamp) {
        return counterTimestamp > 0 ? counterTimestamp : fallbackTimestamp;
    }

    private static boolean withinWindow(long activityEpochMs, long nowEpochMs, long windowMs) {
        return activityEpochMs > 0 && (nowEpochMs - activityEpochMs) <= windowMs;
    }
}
