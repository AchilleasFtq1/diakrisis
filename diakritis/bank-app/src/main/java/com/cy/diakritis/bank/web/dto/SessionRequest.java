package com.cy.diakritis.bank.web.dto;

import com.cy.diakritis.common.dto.Channel;
import com.cy.diakritis.common.dto.Platform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Client-supplied session/device facts attached to every banking action. The server stamps the
 * timestamp itself so the event time is authoritative; the client only describes its channel.
 */
public record SessionRequest(
        @NotBlank String sessionId,
        @NotNull Channel channel,
        String ip,
        @NotBlank String deviceId,
        @NotNull Platform platform
) {
}
