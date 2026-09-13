package com.samvaad.tui.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Reads the authenticated user id from the server-issued access JWT.
 *
 * <p>The server contract states JWT {@code sub} is the user id. Only the
 * payload claim is decoded for sender attribution; the signature is the
 * server's business and is never verified or trusted client-side.
 */
public final class JwtSubject {

    private JwtSubject() {
    }

    /**
     * Extracts the {@code sub} claim as a UUID.
     *
     * @param accessToken server-issued access JWT
     * @return the authenticated user id
     * @throws IllegalArgumentException when the token has no readable subject
     */
    public static UUID subject(String accessToken) {
        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Token has no payload.");
            }
            String payload = new String(
                    Base64.getUrlDecoder().decode(pad(parts[1])), StandardCharsets.UTF_8);
            JsonNode node = new ObjectMapper().readTree(payload);
            JsonNode sub = node.get("sub");
            if (sub == null || sub.asText().isBlank()) {
                throw new IllegalArgumentException("Token has no subject.");
            }
            return UUID.fromString(sub.asText());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid authentication response from server.", e);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid authentication response from server.", e);
        }
    }

    private static String pad(String base64) {
        int missing = (4 - base64.length() % 4) % 4;
        return base64 + "=".repeat(missing);
    }
}
