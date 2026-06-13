package com.cy.diakritis.decision.store;

import com.cy.diakritis.common.persistence.AccountPostureItem;
import com.cy.diakritis.decision.repo.AccountPostureRepository;
import com.cy.diakritis.engine.store.PostureView;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Loads the engine's {@link PostureView} from the {@code AccountPosture} table.
 *
 * <p>The 72h posture is rolling: an absent record (or one whose last activity has decayed past the
 * 72h window) is treated as an empty posture anchored at {@code now}. The K1 signal applies its own
 * 72h decay over {@code lastDepositBreakEpochMs}; this loader only surfaces the stored counters and
 * the empty-default so a cold account scores cleanly.
 */
@Component
public class PostureLoader {

    private static final long WINDOW_MS = 72L * 60L * 60L * 1000L;

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
        // If the last liquidation/limit/beneficiary activity is older than the 72h window, the freed
        // funds no longer count toward the kill-chain posture.
        long lastBreak = item.getLastDepositBreakEpochMs();
        boolean withinWindow = lastBreak > 0 && (now.toEpochMilli() - lastBreak) <= WINDOW_MS;
        if (!withinWindow) {
            return PostureView.empty(now.toEpochMilli());
        }
        return new PostureView(
                item.getFundsFreedEur72hCents(),
                item.getLimitRaised72hCents(),
                item.getBeneficiaryAddCount72h(),
                item.getLastDepositBreakEpochMs());
    }
}
