package com.samvaad.tui.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Verified response shape shared by {@code POST /api/auth/login} and
 * {@code POST /api/auth/refresh}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthResponse(String accessToken, String refreshToken, long expiresIn, String sessionId) {
}
