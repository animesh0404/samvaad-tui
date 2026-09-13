package com.samvaad.tui.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtSubjectTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void extractsSubject() {
        assertEquals(USER_ID, JwtSubject.subject(TestTokens.accessTokenFor(USER_ID)));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> JwtSubject.subject("not-a-jwt"));
        assertThrows(IllegalArgumentException.class, () -> JwtSubject.subject(""));
    }

    @Test
    void rejectsMissingSubject() {
        String token = "h." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"iss\":\"x\"}".getBytes()) + ".s";
        assertThrows(IllegalArgumentException.class, () -> JwtSubject.subject(token));
    }

    @Test
    void rejectsNonUuidSubject() {
        String token = "h." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"alice\"}".getBytes()) + ".s";
        assertThrows(IllegalArgumentException.class, () -> JwtSubject.subject(token));
    }
}
