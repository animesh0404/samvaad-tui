package com.samvaad.tui.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Authenticated device listing ({@code GET /api/e2ee/devices} response):
 * account-level enrollment state plus the owner's devices in server order.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeviceListResponse(
        String enrollmentState,
        List<E2eeDeviceResponse> devices) {
}
