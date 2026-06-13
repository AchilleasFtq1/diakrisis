package com.cy.demobank.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The {@code context.device} object: {@code {device_id, platform}}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceDto(
        String deviceId,
        String platform
) {
}
