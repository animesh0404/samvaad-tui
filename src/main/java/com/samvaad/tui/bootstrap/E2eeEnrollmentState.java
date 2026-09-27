package com.samvaad.tui.bootstrap;

/**
 * Local view of the device's server enrollment lifecycle. The server is
 * authoritative; this value is the last-known server status persisted
 * alongside the local device and reconciled on every enrollment attempt.
 *
 * <ul>
 *   <li>{@code LOCAL_ONLY} — local identity exists but was never enrolled;</li>
 *   <li>{@code PENDING_APPROVAL} — enrollment submitted; the server reports
 *       the device as pending (never treated as active, never
 *       self-approved);</li>
 *   <li>{@code ACTIVE} — the server reports the device as active and bound
 *       to the enrolling session;</li>
 *   <li>{@code REVOKED} — the server reports the device as revoked
 *       (terminal; re-enrollment creates a new local identity, never
 *       resurrects this one).</li>
 * </ul>
 */
public enum E2eeEnrollmentState {
    LOCAL_ONLY,
    PENDING_APPROVAL,
    ACTIVE,
    REVOKED
}
