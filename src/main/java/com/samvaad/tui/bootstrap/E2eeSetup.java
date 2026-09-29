package com.samvaad.tui.bootstrap;

/**
 * Best-effort E2EE setup for one authenticated session: the outbound
 * sender and the inbound mailbox processor, built over the same local
 * device. Either side may be absent independently: a null setup means
 * E2EE is unavailable this session; a null sender means sending is
 * disabled with a reason while the device exists; a null inbox means no
 * mailbox polling (the session is not bound for delivery).
 */
public record E2eeSetup(E2eeMessageSender sender, E2eeInboxProcessor inbox) {
}
