package com.samvaad.tui.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Verified {@code GET /api/users/lookup} response shape: exactly the
 * matched user's id and username. The server never returns email, role,
 * or password material on this endpoint.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserLookupResponse(
        UUID userId,
        String username) {
}
