package com.samvaad.tui.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Properties;
import java.util.UUID;import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class PersistentAuthStoreTest {

    private static final String SECRET = "refresh-secret-token";
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static PersistentAuthStore.StoredCredential credential(String refreshToken) {
        return new PersistentAuthStore.StoredCredential("http://localhost:8080", "alice", USER_ID,
                refreshToken, Instant.parse("2026-09-27T10:00:00Z"));
    }

    @Test
    void missingStoreLoadsNull(@TempDir Path dir) {
        assertNull(new PersistentAuthStore(dir).load());
    }

    @Test
    void saveLoadRoundTrip(@TempDir Path dir) {
        PersistentAuthStore store = new PersistentAuthStore(dir);
        PersistentAuthStore.StoredCredential written = credential(SECRET);

        store.save(written);

        assertEquals(written, store.load());
    }

    @Test
    void saveReplacesPreviousCredential(@TempDir Path dir) {
        PersistentAuthStore store = new PersistentAuthStore(dir);
        store.save(credential("old-token"));

        store.save(credential("new-token"));

        assertEquals("new-token", store.load().refreshToken());
    }

    @Test
    void corruptFileThrowsWithoutLeakingSecret(@TempDir Path dir) throws Exception {
        PersistentAuthStore store = new PersistentAuthStore(dir);
        store.save(credential(SECRET));
        Files.writeString(store.file(), "not-a-properties-file {{{", StandardCharsets.UTF_8);

        AuthStoreException e = assertThrows(AuthStoreException.class, store::load);
        assertTrue(!e.getMessage().contains(SECRET), "exception must not carry the secret");
    }

    @Test
    void missingFieldThrowsWithoutLeaking(@TempDir Path dir) throws Exception {
        PersistentAuthStore store = new PersistentAuthStore(dir);
        Properties props = new Properties();
        props.setProperty("serverUrl", "http://localhost:8080");
        props.setProperty("username", "alice");
        props.setProperty("userId", USER_ID.toString());
        props.setProperty("updatedAt", Instant.now().toString());
        Files.createDirectories(dir);
        try (var writer = Files.newBufferedWriter(store.file(), StandardCharsets.UTF_8)) {
            props.store(writer, null);
        }

        AuthStoreException e = assertThrows(AuthStoreException.class, store::load);
        assertTrue(e.getMessage() != null && !e.getMessage().isBlank());
    }

    @Test
    void deleteRemovesCredential(@TempDir Path dir) {
        PersistentAuthStore store = new PersistentAuthStore(dir);
        store.save(credential(SECRET));

        store.delete();

        assertNull(store.load());
        store.delete();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void savedFileIsOwnerOnly(@TempDir Path dir) throws Exception {
        PersistentAuthStore store = new PersistentAuthStore(dir);
        store.save(credential(SECRET));

        assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(store.file()));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void insecurePermissionsAreRefused(@TempDir Path dir) throws Exception {
        PersistentAuthStore store = new PersistentAuthStore(dir);
        store.save(credential(SECRET));
        Files.setPosixFilePermissions(store.file(), EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ));

        AuthStoreException e = assertThrows(AuthStoreException.class, store::load);
        assertTrue(!e.getMessage().contains(SECRET), "exception must not carry the secret");
    }

    @Test
    void blankRefreshTokenIsRejected() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> credential("  "));
        assertTrue(ex.getMessage() != null && !ex.getMessage().isBlank());
    }

    @Test
    void fileLocationIsStable(@TempDir Path dir) {
        PersistentAuthStore store = new PersistentAuthStore(dir);

        assertEquals(dir.resolve("session.properties"), store.file());
    }
}
