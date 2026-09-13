package com.samvaad.tui.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class CredentialsTest {

    @Test
    void clearZeroesPassword() {
        Credentials credentials = new Credentials("alice", "s3cret".toCharArray());
        credentials.clear();
        assertArrayEquals(new char[]{'\0', '\0', '\0', '\0', '\0', '\0'}, credentials.password());
    }
}
