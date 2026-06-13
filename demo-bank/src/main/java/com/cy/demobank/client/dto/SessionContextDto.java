package com.cy.demobank.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * The {@code context} object: {@code {ts, session_id, channel, ip, device:{device_id, platform}}}.
 * {@code channel} is WEB or MOBILE_APP; {@code platform} is IOS, ANDROID or WEB.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionContextDto(
        Instant ts,
        String sessionId,
        String channel,
        String ip,
        DeviceDto device
) {
}
