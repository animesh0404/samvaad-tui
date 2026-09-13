package com.samvaad.tui.api;

/**
 * Minimal HTTP transport seam. Implementations throw
 * {@link SamvaadApiException} instead of checked exceptions so callers
 * stay free of transport details, and tests can script responses.
 */
public interface HttpTransport {

    /**
     * Sends a POST request.
     *
     * @param baseUrl normalized server base URL without trailing slash
     * @param path request path, e.g. {@code /api/auth/login}
     * @param jsonBody JSON body, or null for a bodyless POST
     * @param bearerToken access token for the Authorization header, or null
     * @return the response status and body
     * @throws SamvaadApiException on transport failure
     */
    HttpResult post(String baseUrl, String path, String jsonBody, String bearerToken);
}
