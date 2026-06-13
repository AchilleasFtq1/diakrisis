package com.cy.diakritis.ops.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The ops view of one account: its rolling risk posture (the funds-freed window the engine remembers
 * for the kill-chain), the device/IP/geo observations seen on it, and its recent decision history.
 * Backs the Account-posture screen and the kill-chain timeline on the decision-detail screen.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountView(
        String accountId,
        Posture posture,
        List<Observation> observations,
        List<FeedEntry> history
) {

    /** The rolling 72h posture the engine carries (the funds-freed window is the kill-chain seed). */
    public record Posture(
            BigDecimal fundsFreedEur72h,
            BigDecimal limitRaisedEur72h,
            long beneficiaryAddCount72h
    ) {
    }

    /** One device/IP/network/alias observation on the account. */
    public record Observation(
            String kind,
            String value,
            Instant firstSeenAt
    ) {
    }
}
