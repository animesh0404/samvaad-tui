package com.samvaad.tui.bootstrap;

import com.samvaad.e2ee.client.CryptoTypes;
import com.samvaad.e2ee.client.SamvaadCryptoService;
import com.samvaad.e2ee.client.SamvaadCryptoServiceImpl;
import com.samvaad.e2ee.client.SignalAdapter;
import com.samvaad.e2ee.client.persist.FileBackedClientCryptoStore;
import com.samvaad.e2ee.client.signal.FilePrivateKeyVault;
import com.samvaad.e2ee.client.signal.LibSignalAdapter;
import com.samvaad.e2ee.client.signal.PrivateKeyVault;
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
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Opens (or first-initializes) the TUI-owned local E2EE device.
 *
 * <p>State directory layout (see {@link E2eePaths}):
 *
 * <pre>
 * &lt;e2eeDir&gt;/
 *   device.properties            non-secret local bootstrap metadata
 *   crypto-vault-v1.dat          encrypted private-key custody (library-owned format)
 *   client-crypto-store-v1.json  crypto-store snapshot (library-owned format)
 * </pre>
 *
 * <p>Only {@code device.properties} is TUI-owned: the local device id,
 * registration id, prekey ids, and the <em>public</em> last-resort Kyber
 * triple (reserved for future enrollment). Vault and snapshot formats and
 * versioning stay owned by {@code e2ee-client}. No OTPKs are generated in
 * this slice: without an upload path, allocating one-time ids now would
 * only complicate the enrollment slice that owns batch policy.
 *
 * <p>Open matrix (fail-closed; an existing identity is never silently
 * replaced):
 *
 * <ul>
 *   <li>no metadata + non-empty directory → refuse (would orphan state);</li>
 *   <li>no metadata + empty directory → generate ids, persist metadata,
 *       open vault/store, provision identity + signed prekey + Kyber;</li>
 *   <li>metadata + provisioned store + non-empty vault → reuse;</li>
 *   <li>metadata + provisioned store + empty vault → refuse (custody
 *       lost);</li>
 *   <li>metadata + unprovisioned store + empty vault → provision (first-init
 *       crash retry);</li>
 *   <li>metadata + unprovisioned store + non-empty vault → refuse (would
 *       orphan sealed material);</li>
 *   <li>wrong vault password, corrupt snapshot, or device/registration
 *       mismatch → refuse via the library, wrapped in
 *       {@link E2eeException}.</li>
 * </ul>
 *
 * <p>Offline-only: the returned runtime's {@link SamvaadCryptoService} uses
 * transport stubs that fail closed until enrollment arrives in a later
 * slice. No network, no login, no server required.
 *
 * <p>The vault password array is zeroed before return in all cases. It is
 * never printed, never stored, and has no environment fallback.
 */
public final class E2eeRuntimeFactory {

    private static final String METADATA_FILE = "device.properties";

    /**
     * Staging file for first-device recovery codes, inside the E2EE data
     * directory but separate from {@code device.properties} (which never
     * stores recovery codes). Presence means "export pending": the Lanterna
     * export flow consumes it and deletes it after a successful export.
     */
    static final String RECOVERY_CODES_STAGING_FILE = "recovery-codes.pending";

    private static final Set<PosixFilePermission> OWNER_ONLY =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final int SIGNED_PREKEY_ID = 1;
    private static final int KYBER_PREKEY_ID = 1;

    private E2eeRuntimeFactory() {
    }

    /**
     * Opens the device in {@code e2eeDir}, initializing it on first use.
     * The password array is zeroed before return.
     */
    public static E2eeRuntime initialize(Path e2eeDir, char[] vaultPassword) {
        Objects.requireNonNull(e2eeDir, "e2eeDir");
        Objects.requireNonNull(vaultPassword, "vaultPassword");
        if (vaultPassword.length == 0) {
            throw new E2eeException("E2EE vault password must not be empty.");
        }
        try {
            Files.createDirectories(e2eeDir);
        } catch (IOException e) {
            throw new E2eeException("Cannot create E2EE state directory: " + e2eeDir, e);
        }
        try {
            return open(e2eeDir, vaultPassword);
        } finally {
            Arrays.fill(vaultPassword, '\0');
        }
    }

    /**
     * Prompts for the vault password over {@code io} and opens the device.
     * Fails clearly when interactive entry is impossible; the password is
     * zeroed after use either way. The prompt distinguishes first-time
     * creation from unlocking: an existing device record means a vault
     * was already created, so its passphrase is requested; otherwise a
     * new passphrase is created. Vault encryption and error handling are
     * unchanged — only the prompt wording branches.
     */
    public static E2eeRuntime initializeInteractive(Path e2eeDir, ConsoleIO io) {
        Objects.requireNonNull(e2eeDir, "e2eeDir");
        Objects.requireNonNull(io, "io");
        char[] password;
        try {
            boolean existing = Files.isRegularFile(e2eeDir.resolve(METADATA_FILE));
            password = existing
                    ? new ConsolePrompter(io).promptE2eeVaultPassword()
                    : new ConsolePrompter(io).promptNewE2eeVaultPassword();
        } catch (RuntimeException e) {
            throw new E2eeException(
                    "E2EE vault requires interactive password entry and none was available.", e);
        }
        return initialize(e2eeDir, password);
    }

    private static E2eeRuntime open(Path dir, char[] password) {
        Path metadataFile = dir.resolve(METADATA_FILE);
        DeviceMetadata metadata = readMetadata(metadataFile);
        if (metadata == null) {
            if (!isEmptyDir(dir)) {
                throw new E2eeException("E2EE state directory has files but no " + METADATA_FILE
                        + "; refusing to invent a replacement identity.");
            }
            metadata = DeviceMetadata.fresh();
            writeMetadata(metadataFile, metadata);
        }
        FilePrivateKeyVault vault;
        try {
            vault = FilePrivateKeyVault.open(dir, metadata.deviceId, password);
        } catch (RuntimeException e) {
            throw new E2eeException(
                    "Cannot unlock the E2EE vault (wrong password or tampered state).", e);
        }
        try {
            FileBackedClientCryptoStore stores;
            try {
                stores = FileBackedClientCryptoStore.open(dir, metadata.deviceId, metadata.registrationId);
            } catch (RuntimeException e) {
                throw new E2eeException("Cannot open the E2EE crypto store.", e);
            }
            LibSignalAdapter adapter = new LibSignalAdapter(metadata.registrationId, vault);
            DeviceMetadata current = reconcile(metadataFile, metadata, vault, stores, adapter);
            byte[] identityPublicKey = stores.identityPublicKey();
            String fingerprint = adapter.fingerprint(identityPublicKey);
            SamvaadCryptoService service = new SamvaadCryptoServiceImpl(
                    adapter, stores, new OfflineTransport(), new OfflineTransport());
            return new E2eeRuntime(current.deviceId, current.registrationId, identityPublicKey,
                    fingerprint, current.kyberPrekeyId, current.kyberPublicKey(),
                    current.kyberSignature(), service, vault, adapter, stores);
        } catch (RuntimeException e) {
            vault.close();
            throw e;
        }
    }

    private static DeviceMetadata reconcile(Path metadataFile, DeviceMetadata metadata,
            FilePrivateKeyVault vault, FileBackedClientCryptoStore stores, LibSignalAdapter adapter) {
        boolean provisioned = stores.isProvisioned();
        boolean vaultHasKeys = vaultHasKeys(vault);
        if (provisioned && vaultHasKeys) {
            if (!metadata.hasKyberTriple()) {
                throw new E2eeException("E2EE state is inconsistent (provisioned store without "
                        + "a recorded Kyber triple); refusing to guess.");
            }
            return metadata;
        }
        if (provisioned) {
            throw new E2eeException(
                    "E2EE vault is empty for a provisioned store; private material is lost.");
        }
        if (vaultHasKeys) {
            throw new E2eeException("E2EE vault holds sealed material for an unprovisioned "
                    + "store; provisioning now would orphan it.");
        }
        SignalAdapter.LocalIdentity identity;
        SignalAdapter.SignedPrekeyPair signed;
        SignalAdapter.KyberPrekeyPair kyber;
        try {
            identity = adapter.generateIdentity();
            signed = adapter.generateSignedPrekey(identity.identityPrivate(), metadata.signedPrekeyId);
            stores.provision(identity, signed);
            kyber = adapter.generateKyberPrekey(stores.identityPrivate(), metadata.kyberPrekeyId);
        } catch (RuntimeException e) {
            throw new E2eeException("Cannot provision the local E2EE device.", e);
        }
        DeviceMetadata withTriple = metadata.withKyberTriple(kyber.publicKey(), kyber.signature());
        writeMetadata(metadataFile, withTriple);
        return withTriple;
    }

    private static boolean vaultHasKeys(FilePrivateKeyVault vault) {
        try {
            for (PrivateKeyVault.KeyKind kind : PrivateKeyVault.KeyKind.values()) {
                if (!vault.handlesOfKind(kind).isEmpty()) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            throw new E2eeException("Cannot inspect the E2EE vault.", e);
        }
    }

    private static boolean isEmptyDir(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        } catch (IOException e) {
            throw new E2eeException("Cannot inspect E2EE state directory: " + dir, e);
        }
    }

    private static DeviceMetadata readMetadata(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            throw new E2eeException("E2EE device metadata is corrupt: " + file, e);
        }
        try {
            return DeviceMetadata.parse(props);
        } catch (IllegalArgumentException e) {
            throw new E2eeException("E2EE device metadata is corrupt: " + file, e);
        }
    }

    private static void writeMetadata(Path file, DeviceMetadata metadata) {
        Path tmp;
        try {
            tmp = Files.createTempFile(
                    Objects.requireNonNull(file.getParent(), "parent"), "device", ".tmp");
        } catch (IOException e) {
            throw new E2eeException("Cannot persist E2EE device metadata: " + file, e);
        }
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            metadata.render().store(writer, null);
            writer.flush();
        } catch (IOException e) {
            throw new E2eeException("Cannot persist E2EE device metadata: " + file, e);
        }
        try {
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new E2eeException("Cannot persist E2EE device metadata: " + file, e);
        }
    }

    /**
     * Persists the authoritative server binding for the device in
     * {@code e2eeDir} without touching identity material: the
     * server-assigned device id, the server-assigned Signal integer, the
     * last-known server status, and the OTPK high-water mark. Never stores
     * recovery codes.
     */
    public static void persistServerBinding(Path e2eeDir, UUID serverDeviceId, int signalDeviceId,
            E2eeEnrollmentState status, int otpkHighWaterMark) {
        Objects.requireNonNull(e2eeDir, "e2eeDir");
        Objects.requireNonNull(serverDeviceId, "serverDeviceId");
        Objects.requireNonNull(status, "status");
        if (signalDeviceId < 1 || otpkHighWaterMark < 0) {
            throw new E2eeException("Invalid server binding.");
        }
        Path metadataFile = e2eeDir.resolve(METADATA_FILE);
        DeviceMetadata metadata = readMetadata(metadataFile);
        if (metadata == null) {
            throw new E2eeException("No local E2EE device to bind a server record to.");
        }
        writeMetadata(metadataFile,
                metadata.withServerBinding(serverDeviceId, signalDeviceId, status, otpkHighWaterMark));
    }

    /**
     * Records which server session the local device is bound to. Written
     * only on fresh enrollment (the server binds the enrolling session at
     * device creation); never changed by adoption, so a restart that
     * restores the same session can prove the binding is still live.
     */
    public static void noteSessionBinding(Path e2eeDir, UUID sessionId) {
        Objects.requireNonNull(e2eeDir, "e2eeDir");
        Objects.requireNonNull(sessionId, "sessionId");
        Path metadataFile = e2eeDir.resolve(METADATA_FILE);
        DeviceMetadata metadata = readMetadata(metadataFile);
        if (metadata == null) {
            throw new E2eeException("No local E2EE device to bind a session to.");
        }
        writeMetadata(metadataFile, metadata.withBoundSession(sessionId));
    }

    /**
     * Loads the persisted server binding, or null when this device was
     * never enrolled. Package-visible for enrollment reconciliation.
     */
    static ServerBinding loadServerBinding(Path e2eeDir) {
        Objects.requireNonNull(e2eeDir, "e2eeDir");
        DeviceMetadata metadata = readMetadata(e2eeDir.resolve(METADATA_FILE));
        if (metadata == null || metadata.serverDeviceIdOrNull == null) {
            return null;
        }
        return new ServerBinding(metadata.serverDeviceIdOrNull, metadata.signalDeviceIdOrNull,
                metadata.enrollmentStatusOrNull, metadata.otpkHighWaterMark,
                metadata.boundSessionIdOrNull);
    }

    /**
     * Last-known authoritative server record for the local device. The
     * server remains authoritative; this is a cache reconciled on every
     * enrollment attempt.
     */
    record ServerBinding(UUID serverDeviceId, int signalDeviceId, E2eeEnrollmentState status,
            int otpkHighWaterMark, UUID boundSessionIdOrNull) {
    }

    /**
     * Staging-file path for first-device recovery codes.
     */
    static Path recoveryCodesStagingFile(Path e2eeDir) {
        Objects.requireNonNull(e2eeDir, "e2eeDir");
        return e2eeDir.resolve(RECOVERY_CODES_STAGING_FILE);
    }

    /**
     * Stages freshly issued first-device recovery codes for the later
     * Lanterna export flow. Called only on fresh enrollment with a
     * non-empty issued set; adoption, normal login, and restarts never
     * reach here.
     *
     * <p>Fails closed: an existing staging file (pending export) is never
     * overwritten, partial writes never land (temp file + atomic move),
     * and every failure throws without exposing code values. Callers must
     * degrade E2EE setup rather than print the codes anywhere.
     */
    static void stageRecoveryCodes(Path e2eeDir, List<String> codes) {
        Objects.requireNonNull(e2eeDir, "e2eeDir");
        Objects.requireNonNull(codes, "codes");
        if (codes.isEmpty()) {
            throw new E2eeException("No recovery codes to stage.");
        }
        for (String code : codes) {
            if (code == null || code.isBlank()) {
                throw new E2eeException("Cannot stage an incomplete recovery-code set.");
            }
        }
        Path file = recoveryCodesStagingFile(e2eeDir);
        if (Files.exists(file)) {
            throw new E2eeException("A pending recovery-code export already exists: " + file);
        }
        try {
            Files.createDirectories(e2eeDir);
        } catch (IOException e) {
            throw new E2eeException("Cannot stage recovery codes: " + file, e);
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile(e2eeDir, "recovery-codes", ".tmp");
            restrict(tmp);
        } catch (IOException e) {
            throw new E2eeException("Cannot stage recovery codes: " + file, e);
        }
        try {
            StringBuilder staged = new StringBuilder("Samvaad Recovery Codes\n");
            staged.append("======================\n");
            staged.append("\n");
            staged.append("These codes are single-use recovery credentials.\n");
            staged.append("Store this file securely. They are not regenerated automatically.\n");
            staged.append("\n");
            for (String code : codes) {
                staged.append(code.trim()).append('\n');
            }
            Files.writeString(tmp, staged.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file);
            }
            restrict(file);
        } catch (IOException e) {
            throw new E2eeException("Cannot stage recovery codes: " + file, e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // Best effort: the temp name is unique and owner-restricted.
                }
            }
        }
    }

    /**
     * Owner-only file protection, mirroring the session credential store
     * convention: POSIX 0600 where supported, no-op on non-POSIX
     * platforms (e.g. Windows) whose profile directories already limit
     * access.
     */
    private static void restrict(Path target) {
        try {
            Files.setPosixFilePermissions(target, OWNER_ONLY);
        } catch (UnsupportedOperationException e) {
            // Non-POSIX platform: no portable finer-grained equivalent here.
        } catch (IOException e) {
            throw new E2eeException("Cannot protect recovery-code staging file: " + target, e);
        }
    }
    /**
     * Offline transport: fails closed until a later slice enrolls the device
     * and provides real claim/submit clients. Unchecked, so the declared
     * library signatures need no adaptation.
     */
    private static final class OfflineTransport implements SamvaadCryptoService.ClaimClient,
            SamvaadCryptoService.SubmitClient {

        @Override
        public CryptoTypes.RecipientBundle claim(UUID recipientDeviceId, UUID claimRequestId) {
            throw new E2eeException(
                    "E2EE device is not enrolled: prekey claims arrive in a later slice.");
        }

        @Override
        public void submit(UUID messageRequestId, List<CryptoTypes.OutboundEnvelope> envelopes) {
            throw new E2eeException(
                    "E2EE device is not enrolled: message transport arrives in a later slice.");
        }
    }

    /**
     * Non-secret local bootstrap metadata. The Kyber triple is public key
     * material reserved for future enrollment; everything else identifies
     * the device to the local store and vault.
     */
    private static final class DeviceMetadata {

        final UUID deviceId;
        final int registrationId;
        final int signedPrekeyId;
        final int kyberPrekeyId;
        final byte[] kyberPublicKeyOrNull;
        final byte[] kyberSignatureOrNull;
        final UUID serverDeviceIdOrNull;
        final int signalDeviceIdOrNull;
        final E2eeEnrollmentState enrollmentStatusOrNull;
        final int otpkHighWaterMark;
        final UUID boundSessionIdOrNull;

        private DeviceMetadata(UUID deviceId, int registrationId, int signedPrekeyId,
                int kyberPrekeyId, byte[] kyberPublicKeyOrNull, byte[] kyberSignatureOrNull,
                UUID serverDeviceIdOrNull, int signalDeviceIdOrNull,
                E2eeEnrollmentState enrollmentStatusOrNull, int otpkHighWaterMark,
                UUID boundSessionIdOrNull) {
            this.deviceId = deviceId;
            this.registrationId = registrationId;
            this.signedPrekeyId = signedPrekeyId;
            this.kyberPrekeyId = kyberPrekeyId;
            this.kyberPublicKeyOrNull = kyberPublicKeyOrNull;
            this.kyberSignatureOrNull = kyberSignatureOrNull;
            this.serverDeviceIdOrNull = serverDeviceIdOrNull;
            this.signalDeviceIdOrNull = signalDeviceIdOrNull;
            this.enrollmentStatusOrNull = enrollmentStatusOrNull;
            this.otpkHighWaterMark = otpkHighWaterMark;
            this.boundSessionIdOrNull = boundSessionIdOrNull;
        }

        static DeviceMetadata fresh() {
            return new DeviceMetadata(UUID.randomUUID(),
                    ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE),
                    SIGNED_PREKEY_ID, KYBER_PREKEY_ID, null, null, null, 0, null, 0, null);
        }

        static DeviceMetadata parse(Properties props) {
            String deviceText = props.getProperty("deviceId");
            String regText = props.getProperty("registrationId");
            String signedText = props.getProperty("signedPrekeyId");
            String kyberIdText = props.getProperty("kyberPrekeyId");
            if (deviceText == null || regText == null || signedText == null || kyberIdText == null) {
                throw new IllegalArgumentException("missing required device metadata");
            }
            UUID deviceId = UUID.fromString(deviceText.trim());
            int registrationId = Integer.parseInt(regText.trim());
            int signedPrekeyId = Integer.parseInt(signedText.trim());
            int kyberPrekeyId = Integer.parseInt(kyberIdText.trim());
            if (registrationId < 1) {
                throw new IllegalArgumentException("registrationId out of range");
            }
            String kyberKeyText = props.getProperty("kyberPublicKey");
            String kyberSigText = props.getProperty("kyberSignature");
            byte[] kyberPublicKey = null;
            byte[] kyberSignature = null;
            if (kyberKeyText != null || kyberSigText != null) {
                if (kyberKeyText == null || kyberSigText == null) {
                    throw new IllegalArgumentException("partial Kyber triple in device metadata");
                }
                kyberPublicKey = Base64.getDecoder().decode(kyberKeyText.trim());
                kyberSignature = Base64.getDecoder().decode(kyberSigText.trim());
                if (kyberPublicKey.length == 0 || kyberSignature.length == 0) {
                    throw new IllegalArgumentException("empty Kyber triple in device metadata");
                }
            }
            String serverDeviceText = props.getProperty("serverDeviceId");
            String signalDeviceText = props.getProperty("signalDeviceId");
            String statusText = props.getProperty("enrollmentStatus");
            String otpkMarkText = props.getProperty("otpkHighWaterMark");
            UUID serverDeviceId = null;
            int signalDeviceId = 0;
            E2eeEnrollmentState status = null;
            int otpkMark = 0;
            UUID boundSessionId = null;
            if (serverDeviceText != null || signalDeviceText != null
                    || statusText != null || otpkMarkText != null) {
                if (serverDeviceText == null || signalDeviceText == null
                        || statusText == null || otpkMarkText == null) {
                    throw new IllegalArgumentException("partial server binding in device metadata");
                }
                serverDeviceId = UUID.fromString(serverDeviceText.trim());
                signalDeviceId = Integer.parseInt(signalDeviceText.trim());
                try {
                    status = E2eeEnrollmentState.valueOf(statusText.trim());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("unknown enrollment status in device metadata", e);
                }
                otpkMark = Integer.parseInt(otpkMarkText.trim());
                if (signalDeviceId < 1 || otpkMark < 0) {
                    throw new IllegalArgumentException("server binding out of range");
                }
            }
            String boundSessionText = props.getProperty("boundSessionId");
            if (boundSessionText != null) {
                try {
                    boundSessionId = UUID.fromString(boundSessionText.trim());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("invalid bound session in device metadata", e);
                }
            }
            return new DeviceMetadata(deviceId, registrationId, signedPrekeyId, kyberPrekeyId,
                    kyberPublicKey, kyberSignature, serverDeviceId, signalDeviceId, status, otpkMark,
                    boundSessionId);
        }

        boolean hasKyberTriple() {
            return kyberPublicKeyOrNull != null && kyberSignatureOrNull != null;
        }

        byte[] kyberPublicKey() {
            if (kyberPublicKeyOrNull == null) {
                throw new E2eeException("E2EE device metadata has no recorded Kyber triple.");
            }
            return Arrays.copyOf(kyberPublicKeyOrNull, kyberPublicKeyOrNull.length);
        }

        byte[] kyberSignature() {
            if (kyberSignatureOrNull == null) {
                throw new E2eeException("E2EE device metadata has no recorded Kyber triple.");
            }
            return Arrays.copyOf(kyberSignatureOrNull, kyberSignatureOrNull.length);
        }

        DeviceMetadata withKyberTriple(byte[] publicKey, byte[] signature) {
            return new DeviceMetadata(deviceId, registrationId, signedPrekeyId, kyberPrekeyId,
                    Arrays.copyOf(publicKey, publicKey.length),
                    Arrays.copyOf(signature, signature.length),
                    serverDeviceIdOrNull, signalDeviceIdOrNull, enrollmentStatusOrNull,
                    otpkHighWaterMark, boundSessionIdOrNull);
        }

        DeviceMetadata withServerBinding(UUID serverDeviceId, int signalDeviceId,
                E2eeEnrollmentState status, int mark) {
            return new DeviceMetadata(deviceId, registrationId, signedPrekeyId, kyberPrekeyId,
                    kyberPublicKeyOrNull, kyberSignatureOrNull, serverDeviceId, signalDeviceId,
                    status, mark, boundSessionIdOrNull);
        }

        DeviceMetadata withBoundSession(UUID sessionId) {
            return new DeviceMetadata(deviceId, registrationId, signedPrekeyId, kyberPrekeyId,
                    kyberPublicKeyOrNull, kyberSignatureOrNull, serverDeviceIdOrNull,
                    signalDeviceIdOrNull, enrollmentStatusOrNull, otpkHighWaterMark, sessionId);
        }

        Properties render() {
            Properties props = new Properties();
            props.setProperty("deviceId", deviceId.toString());
            props.setProperty("registrationId", Integer.toString(registrationId));
            props.setProperty("signedPrekeyId", Integer.toString(signedPrekeyId));
            props.setProperty("kyberPrekeyId", Integer.toString(kyberPrekeyId));
            if (hasKyberTriple()) {
                props.setProperty("kyberPublicKey",
                        Base64.getEncoder().encodeToString(kyberPublicKeyOrNull));
                props.setProperty("kyberSignature",
                        Base64.getEncoder().encodeToString(kyberSignatureOrNull));
            }
            if (serverDeviceIdOrNull != null) {
                props.setProperty("serverDeviceId", serverDeviceIdOrNull.toString());
                props.setProperty("signalDeviceId", Integer.toString(signalDeviceIdOrNull));
                props.setProperty("enrollmentStatus", enrollmentStatusOrNull.name());
                props.setProperty("otpkHighWaterMark", Integer.toString(otpkHighWaterMark));
            }
            if (boundSessionIdOrNull != null) {
                props.setProperty("boundSessionId", boundSessionIdOrNull.toString());
            }
            return props;
        }
    }
}
