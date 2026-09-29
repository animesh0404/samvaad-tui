package com.samvaad.tui.bootstrap;

/**
 * Best-effort E2EE setup for one authenticated session: the outbound
 * sender, the inbound mailbox processor, and the pending first-enrollment
 * recovery-code export, built over the same local device. Each side may
 * be absent independently: a null setup means E2EE is unavailable this
 * session; a null sender means sending is disabled with a reason while
 * the device exists; a null inbox means no mailbox polling (the session
 * is not bound for delivery); a null export means no pending
 * recovery-code export (already exported or never issued).
 */
public record E2eeSetup(E2eeMessageSender sender, E2eeInboxProcessor inbox,
        RecoveryCodesExport recoveryExport) {
}
