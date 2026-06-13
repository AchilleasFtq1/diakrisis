package com.cy.diakritis.ops.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A recorded outcome on the ops "wins" board: a CONFIRMED_SAVE (a true catch — money protected) or a
 * FALSE_POSITIVE (a hold that interrupted a legitimate payment). Projected from {@code Outcomes}; the
 * {@code signalPattern} is the firing-signal fingerprint the lifecycle recorded at resolution time.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OutcomeView(
        String eventId,
        String accountId,
        String outcome,
        BigDecimal amountEur,
        String signalPattern,
        Instant at
) {
}
