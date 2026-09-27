package com.samvaad.tui.bootstrap;

import com.samvaad.e2ee.client.ClientCryptoStore;
import com.samvaad.e2ee.client.SamvaadCryptoService;
import com.samvaad.e2ee.client.SignalAdapter;
import com.samvaad.e2ee.client.signal.FilePrivateKeyVault;
import com.samvaad.e2ee.client.signal.LibSignalAdapter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One opened local E2EE device: durable identity plus the
 * {@link SamvaadCryptoService} built over it.
 *
 * <p>Chain of ownership: this runtime → {@code e2ee-client} →
 * {@link SamvaadCryptoService} → {@link LibSignalAdapter} →
 * {@link FilePrivateKeyVault} + {@link ClientCryptoStore}. Created only via
 * {@link E2eeRuntimeFactory}; the UI layer never sees vaults, adapters,
 * stores, or private handles.
 *
 * <p>Only public key material crosses this boundary (identity public key,
 * fingerprint, last-resort Kyber public triple reserved for future
 * enrollment). Private key bytes are never exposed: the library deals in
 * sealed handles owned by the adapter/vault pair.
 *
 * <p>Single-process ownership, mirroring the library contract. Close when
 * the process (or test) is done with the device; close zeroes vault key
 * material and refuses further use.
 */
public final class E2eeRuntime implements AutoCloseable {

    private final UUID deviceId;
    private final int registrationId;
    private final byte[] identityPublicKey;
    private final String fingerprint;
    private final int kyberPrekeyId;
    private final byte[] kyberPublicKey;
    private final byte[] kyberSignature;
    private final SamvaadCryptoService service;
    private final FilePrivateKeyVault vault;
    private final LibSignalAdapter adapter;
    private final ClientCryptoStore stores;
    private boolean closed;

    /**
     * One generated one-time prekey, public half only. The private half is
     * sealed in the vault at generation time and never leaves this runtime.
     */
    public record OneTimePublicKey(int prekeyId, byte[] publicKey) {
        public OneTimePublicKey {
            Objects.requireNonNull(publicKey, "publicKey");
            publicKey = Arrays.copyOf(publicKey, publicKey.length);
        }
    }

    E2eeRuntime(UUID deviceId,
            int registrationId,
            byte[] identityPublicKey,
            String fingerprint,
            int kyberPrekeyId,
            byte[] kyberPublicKey,
            byte[] kyberSignature,
            SamvaadCryptoService service,
            FilePrivateKeyVault vault,
            LibSignalAdapter adapter,
            ClientCryptoStore stores) {
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        this.registrationId = registrationId;
        this.identityPublicKey = Arrays.copyOf(
                Objects.requireNonNull(identityPublicKey, "identityPublicKey"),
                identityPublicKey.length);
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.kyberPrekeyId = kyberPrekeyId;
        this.kyberPublicKey = Arrays.copyOf(
                Objects.requireNonNull(kyberPublicKey, "kyberPublicKey"),
                kyberPublicKey.length);
        this.kyberSignature = Arrays.copyOf(
                Objects.requireNonNull(kyberSignature, "kyberSignature"),
                kyberSignature.length);
        this.service = Objects.requireNonNull(service, "service");
        this.vault = Objects.requireNonNull(vault, "vault");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.stores = Objects.requireNonNull(stores, "stores");
    }

    /**
     * Stable local device identifier, generated once at first initialization
     * and reused on every restart. Local-only until future server enrollment.
     */
    public UUID deviceId() {
        return deviceId;
    }

    /**
     * Stable local registration id, generated once alongside the device id.
     */
    public int registrationId() {
        return registrationId;
    }

    /**
     * This device's canonical identity public key bytes (defensive copy).
     * The restart invariant is byte-equality of this value for the same
     * state directory and correct vault password.
     */
    public byte[] identityPublicKey() {
        ensureOpen();
        return Arrays.copyOf(identityPublicKey, identityPublicKey.length);
    }

    /**
     * Opaque human-verification display string for the identity public key,
     * as produced by {@link SignalAdapter#fingerprint(byte[])}. Display only;
     * trust decisions compare {@link #identityPublicKey()} bytes.
     */
    public String fingerprint() {
        ensureOpen();
        return fingerprint;
    }

    /**
     * Last-resort Kyber prekey id, reserved for future server enrollment.
     */
    public int kyberPrekeyId() {
        return kyberPrekeyId;
    }

    /**
     * Last-resort Kyber public key bytes (defensive copy), reserved for
     * future server enrollment.
     */
    public byte[] kyberPublicKey() {
        ensureOpen();
        return Arrays.copyOf(kyberPublicKey, kyberPublicKey.length);
    }

    /**
     * Identity signature over the Kyber public key (defensive copy),
     * reserved for future server enrollment.
     */
    public byte[] kyberSignature() {
        ensureOpen();
        return Arrays.copyOf(kyberSignature, kyberSignature.length);
    }

    /**
     * The crypto service over this device. Transport is offline in this
     * slice: claim/submit calls fail closed with {@link E2eeException}
     * until enrollment arrives in a later slice.
     */
    public SamvaadCryptoService service() {
        ensureOpen();
        return service;
    }

    /**
     * Signed-prekey id from the provisioned store.
     */
    public int signedPrekeyId() {
        ensureOpen();
        return stores.signedPrekey().prekeyId();
    }

    /**
     * Signed-prekey public bytes (defensive copy). The private half stays
     * sealed; only this public half is uploaded at enrollment.
     */
    public byte[] signedPrekeyPublicKey() {
        ensureOpen();
        byte[] publicKey = stores.signedPrekey().publicKey();
        return Arrays.copyOf(publicKey, publicKey.length);
    }

    /**
     * Identity signature over the signed prekey (defensive copy).
     */
    public byte[] signedPrekeySignature() {
        ensureOpen();
        byte[] signature = stores.signedPrekey().signature();
        return Arrays.copyOf(signature, signature.length);
    }

    /**
     * Generates {@code count} one-time prekeys starting at {@code startId},
     * seals each private half in the vault (registered in the store), and
     * returns the public halves for upload. Id allocation stays with the
     * caller, which must persist its high-water mark; ids must never repeat
     * for this device.
     */
    public List<OneTimePublicKey> generateOneTimePrekeys(int startId, int count) {
        ensureOpen();
        if (startId < 0 || count <= 0) {
            throw new E2eeException("Invalid one-time prekey range.");
        }
        List<OneTimePublicKey> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int id = startId + i;
            SignalAdapter.OneTimePrekeyPair pair = adapter.generateOneTimePrekey(id);
            stores.putOneTimePrivate(id, pair.privateHandle());
            batch.add(new OneTimePublicKey(id, pair.publicKey()));
        }
        return List.copyOf(batch);
    }

    /**
     * Whether a sealed one-time private handle exists locally for
     * {@code prekeyId}. Existence only — private bytes never cross this
     * boundary. Package-visible for enrollment verification tests.
     */
    boolean hasOneTimePrivate(int prekeyId) {
        ensureOpen();
        return stores.oneTimePrivate(prekeyId).isPresent();
    }

    /**
     * Locks the vault (zeroing key material) and refuses further use.
     * Idempotent.
     */
    @Override
    public void close() {
        closed = true;
        vault.close();
    }

    private void ensureOpen() {
        if (closed) {
            throw new E2eeException("E2EE runtime is closed.");
        }
    }
}
