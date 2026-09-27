package com.samvaad.tui.api.dto;

import java.util.List;

/**
 * OTPK batch upload ({@code PUT /api/e2ee/devices/{id}/one-time-prekeys}).
 * The server requires exactly {@code PrekeyManager.BATCH_SIZE} (100)
 * entries per upload.
 */
public record UploadPrekeysRequest(
        List<OneTimePrekeyRequest> prekeys) {
}
