package com.samvaad.tui.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Verified {@code POST /api/auth/login} request body.
 *
 * <p>{@code installationId} is optional server metadata; this client
 * always sends it as JSON null and never generates one.
 */
public record LoginRequest(
        String identifier,
        String password,
        @JsonInclude(JsonInclude.Include.ALWAYS) Object installationId,
        String clientPlatform,
        String clientName,
        String clientVersion) {
}
