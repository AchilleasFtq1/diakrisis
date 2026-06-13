package com.cy.diakritis.ops.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * A single decision in the ops activity feed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FeedEntry(
        String eventId,
        String accountId,
        String initiatorSub,
        String lifecycleState,
        Instant createdAt,
        Instant holdExpiresAt
) {
}
