package com.samvaad.tui.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Enrollment outcome ({@code POST /api/e2ee/devices} response).
 * {@code recoveryCodes} is present only on a successful first-device
 * bootstrap, carries the plaintext set exactly once, and must be shown to
 * the user once — never persisted.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EnrollDeviceResponse(
        E2eeDeviceResponse device,
        String enrollmentState,
        List<String> recoveryCodes) {
}
