package com.cy.diakritis.ops.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A single decision in the ops activity feed. The lifecycle fields come straight off the
 * {@code Decisions} table; the engine projection ({@code verdict}, {@code score}, {@code typologies},
 * {@code reasonCode}, {@code friction}, {@code amountEur}, {@code eventType}) is parsed from the
 * stored verbatim decision response so the console can show the engine's reasoning, not just state.
 * A null engine field means it wasn't present on the stored decision (the console flags it).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FeedEntry(
        String eventId,
        String accountId,
        String initiatorSub,
        String lifecycleState,
        Instant createdAt,
        Instant holdExpiresAt,
        String verdict,
        Integer score,
        List<String> typologies,
        String reasonCode,
        String friction,
        BigDecimal amountEur,
        String eventType
) {
}
