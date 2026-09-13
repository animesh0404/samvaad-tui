package com.samvaad.tui.api.dto;

/**
 * Verified {@code POST /api/auth/refresh} request body.
 */
public record RefreshRequest(String refreshToken) {
}
