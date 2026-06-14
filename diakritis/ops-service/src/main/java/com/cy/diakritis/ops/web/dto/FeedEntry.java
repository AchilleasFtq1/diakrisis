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
 *
 * <p>{@code decisionUnreadable} disambiguates the two ways the engine projection can be null: a
 * legitimately engine-less row (no stored response) versus a row whose stored response JSON failed to
 * parse (corrupt or schema-incompatible). When {@code true}, the engine fields are null because the
 * stored decision could not be read — the console renders an explicit error badge instead of treating
 * it as a benign engine-less decision, so a systematic parse break is visible rather than swallowed.
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
        String eventType,
        boolean decisionUnreadable
) {
}
