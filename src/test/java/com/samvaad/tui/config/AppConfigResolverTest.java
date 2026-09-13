package com.samvaad.tui.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AppConfigResolverTest {

    @Test
    void resolvesAndTrims() {
        AppConfig config = AppConfigResolver.resolve("http://localhost:8080", " alice ");
        assertEquals("http://localhost:8080", config.serverUrl());
        assertEquals("alice", config.username());
    }

    @Test
    void stripsTrailingSlash() {
        AppConfig config = AppConfigResolver.resolve("http://localhost:8080///", "alice");
        assertEquals("http://localhost:8080", config.serverUrl());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsBlankServerUrl(String url) {
        assertThrows(IllegalArgumentException.class, () -> AppConfigResolver.resolve(url, "alice"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsBlankUsername(String username) {
        assertThrows(IllegalArgumentException.class,
                () -> AppConfigResolver.resolve("http://localhost:8080", username));
    }

    @Test
    void rejectsMissingScheme() {
        assertThrows(IllegalArgumentException.class,
                () -> AppConfigResolver.resolve("localhost:8080", "alice"));
    }

    @Test
    void rejectsNullValues() {
        assertThrows(IllegalArgumentException.class, () -> AppConfigResolver.resolve(null, "alice"));
        assertThrows(IllegalArgumentException.class,
                () -> AppConfigResolver.resolve("http://localhost:8080", null));
    }
}
