package com.samvaad.tui.bootstrap;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * An encrypted send that did not reach every recipient device. Carries the
 * per-device library outcomes ({@code SENT}, {@code PAUSED_KEY_CHANGED},
 * {@code SKIPPED_REVOKED}, {@code DEFERRED_TRANSIENT},
 * {@code FAILED_CORRUPT}) so callers can report precisely.
 *
 * <p>Never triggers a plaintext fallback: callers surface this failure and
 * retry the same logical message (same request id) when the outcome is
 * retryable.
 */
public class E2eeSendException extends E2eeException {

    private final Map<UUID, String> outcomes;

    public E2eeSendException(String message, Map<UUID, String> outcomes) {
        super(message);
        this.outcomes = Map.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
    }

    public E2eeSendException(String message, Map<UUID, String> outcomes, Throwable cause) {
        super(message, cause);
        this.outcomes = Map.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
    }

    /**
     * Per-device outcomes, empty when the failure happened before any
     * device was attempted (no directory, disabled sender, transport down).
     */
    public Map<UUID, String> outcomes() {
        return outcomes;
    }
}
