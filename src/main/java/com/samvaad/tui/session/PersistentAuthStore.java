package com.samvaad.tui.session;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * The single persisted authentication credential: the latest rotating
 * server refresh token plus the non-secret metadata needed to use it.
 * Lives in its own protected file, separate from the E2EE vault/store
 * (different trust domain, different lifecycle).
 *
 * <p>Only the refresh token is secret at rest. Never stored here:
 * passwords, access tokens, private keys, crypto handles, E2EE state,
 * recovery codes.
 *
 * <p>Rotation is atomic: a successful refresh replaces the file in one
 * move, so the old token never remains the active credential. On POSIX
 * the file is created and kept {@code 0600}; a credential file readable
 * by anyone else is refused at load time (fail closed — delete it and
 * log in again). No in-memory zeroization is attempted for the token
 * string itself; the protected file is the control.
 */
public final class PersistentAuthStore {

    static final String FILE_NAME = "session.properties";

    private static final Set<PosixFilePermission> OWNER_ONLY =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    /**
     * Persisted credential. The refresh token is the sole secret.
     */
    public record StoredCredential(String serverUrl, String username, UUID userId,
            String refreshToken, Instant updatedAt) {
        public StoredCredential {
            Objects.requireNonNull(serverUrl, "serverUrl");
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(refreshToken, "refreshToken");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (refreshToken.isBlank()) {
                throw new IllegalArgumentException("refreshToken must not be blank.");
            }
        }
    }

    private final Path dir;
    private final Path file;

    public PersistentAuthStore(Path dir) {
        this.dir = Objects.requireNonNull(dir, "dir");
        this.file = dir.resolve(FILE_NAME);
    }

    /**
     * Path of the credential file (lets tests and diagnostics locate it
     * without ever reading the secret).
     */
    public Path file() {
        return file;
    }

    /**
     * Loads the credential, or null when no credential was ever persisted.
     *
     * @throws AuthStoreException on unreadable, corrupt, or insecurely
     *         permissioned files
     */
    public StoredCredential load() {
        if (!Files.exists(file)) {
            return null;
        }
        requireOwnerOnly();
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            throw new AuthStoreException("Authentication credential is corrupt: " + file, e);
        }
        try {
            String serverUrl = required(props, "serverUrl");
            String username = required(props, "username");
            UUID userId = UUID.fromString(required(props, "userId").trim());
            String refreshToken = required(props, "refreshToken");
            Instant updatedAt = Instant.parse(required(props, "updatedAt").trim());
            if (refreshToken.isBlank()) {
                throw new IllegalArgumentException("blank refreshToken");
            }
            return new StoredCredential(serverUrl.trim(), username.trim(), userId,
                    refreshToken, updatedAt);
        } catch (IllegalArgumentException e) {
            throw new AuthStoreException("Authentication credential is corrupt: " + file, e);
        }
    }

    /**
     * Atomically replaces the credential (rotation). Fails closed: any
     * failure throws and the previous file content is left untouched.
     *
     * @throws AuthStoreException when the credential cannot be made durable
     */
    public void save(StoredCredential credential) {
        Objects.requireNonNull(credential, "credential");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new AuthStoreException("Cannot create auth state directory: " + dir, e);
        }
        Properties props = new Properties();
        props.setProperty("serverUrl", credential.serverUrl());
        props.setProperty("username", credential.username());
        props.setProperty("userId", credential.userId().toString());
        props.setProperty("refreshToken", credential.refreshToken());
        props.setProperty("updatedAt", credential.updatedAt().toString());
        Path tmp;
        try {
            tmp = Files.createTempFile(dir, "session", ".tmp");
            restrict(tmp);
        } catch (IOException e) {
            throw new AuthStoreException("Cannot persist authentication credential: " + file, e);
        }
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            props.store(writer, null);
            writer.flush();
        } catch (IOException e) {
            throw new AuthStoreException("Cannot persist authentication credential: " + file, e);
        }
        try {
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            restrict(file);
        } catch (IOException e) {
            throw new AuthStoreException("Cannot persist authentication credential: " + file, e);
        }
    }

    /**
     * Deletes the credential. Used only by explicit logout (after a known
     * outcome) and by recovery from corrupt/invalid credentials.
     *
     * @throws AuthStoreException when deletion fails
     */
    public void delete() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new AuthStoreException("Cannot delete authentication credential: " + file, e);
        }
    }

    private static String required(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return value;
    }

    private void requireOwnerOnly() {
        Set<PosixFilePermission> permissions;
        try {
            permissions = Files.getPosixFilePermissions(file);
        } catch (UnsupportedOperationException e) {
            return;
        } catch (IOException e) {
            throw new AuthStoreException("Cannot inspect authentication credential: " + file, e);
        }
        for (PosixFilePermission permission : permissions) {
            if (permission != PosixFilePermission.OWNER_READ
                    && permission != PosixFilePermission.OWNER_WRITE) {
                throw new AuthStoreException("Authentication credential is readable by others: "
                        + file + " (fix permissions or delete it and log in again).");
            }
        }
    }

    private static void restrict(Path target) {
        try {
            Files.setPosixFilePermissions(target, OWNER_ONLY);
        } catch (UnsupportedOperationException e) {
            // Non-POSIX platform (e.g. Windows): the user profile directory
            // already limits access; no portable finer-grained equivalent here.
        } catch (IOException e) {
            throw new AuthStoreException("Cannot protect authentication credential: " + target, e);
        }
    }
}
