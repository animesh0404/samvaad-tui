package com.samvaad.tui.bootstrap;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Minimal versioned application payload framed around UI text before Signal
 * encryption: {@code UI text → application payload → Signal encryption →
 * ciphertext}. The crypto layer receives these bytes as its opaque
 * {@code plaintextAssoc}; Signal never sees ad-hoc encodings.
 *
 * <p>Wire layout (all integers single unsigned bytes):
 *
 * <pre>
 * [0]  format version (1)
 * [1]  message type (1 = direct text)
 * [2..] UTF-8 content
 * </pre>
 *
 * <p>Deterministic and independent of Signal so future Web clients can
 * decode the same bytes. Strict on decode: unknown versions or types fail
 * closed rather than guessing.
 */
public final class E2eePayload {

    static final byte FORMAT_VERSION = 1;
    static final byte TYPE_DIRECT_TEXT = 1;

    private E2eePayload() {
    }

    /**
     * Decoded application payload.
     */
    public record DecodedPayload(byte version, byte type, String content) {
    }

    /**
     * Frames direct-message text. Content parity mirrors the plaintext
     * composer (max 200 chars); no separate transport cap is invented.
     */
    public static byte[] encodeDirectText(String content) {
        Objects.requireNonNull(content, "content");
        byte[] text = content.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[2 + text.length];
        out[0] = FORMAT_VERSION;
        out[1] = TYPE_DIRECT_TEXT;
        System.arraycopy(text, 0, out, 2, text.length);
        return out;
    }

    /**
     * Decodes bytes produced by {@link #encodeDirectText}.
     *
     * @throws E2eeException on truncated input or unknown version/type
     */
    public static DecodedPayload decode(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length < 2) {
            throw new E2eeException("E2EE application payload is truncated.");
        }
        if (payload[0] != FORMAT_VERSION) {
            throw new E2eeException("Unsupported E2EE application payload version.");
        }
        if (payload[1] != TYPE_DIRECT_TEXT) {
            throw new E2eeException("Unsupported E2EE application payload type.");
        }
        return new DecodedPayload(payload[0], payload[1],
                new String(Arrays.copyOfRange(payload, 2, payload.length), StandardCharsets.UTF_8));
    }
}
