package com.samvaad.tui.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Builds unsigned JWT-shaped access tokens for tests. Signatures are
 * never verified by the client, so fixtures only need a readable payload.
 */
public final class TestTokens {

    private TestTokens() {
    }

    public static String accessTokenFor(UUID subject) {
        return base64("{\"alg\":\"none\"}")
                + "." + base64("{\"sub\":\"" + subject + "\"}")
                + ".sig";
    }

    private static String base64(String text) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
