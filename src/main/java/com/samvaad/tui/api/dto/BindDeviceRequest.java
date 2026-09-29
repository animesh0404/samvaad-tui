package com.samvaad.tui.api.dto;

/**
 * Existing-device recovery rebind request ({@code POST
 * /api/e2ee/devices/{deviceId}/bind} body). Carries exactly one recovery
 * code; no key material of any kind is accepted here.
 */
public record BindDeviceRequest(String recoveryCode) {
}
