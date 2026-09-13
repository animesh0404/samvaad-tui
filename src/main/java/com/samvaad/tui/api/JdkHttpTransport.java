package com.samvaad.tui.api;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * {@link HttpTransport} backed by {@link HttpClient}.
 */
public final class JdkHttpTransport implements HttpTransport {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client;

    public JdkHttpTransport() {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    JdkHttpTransport(HttpClient client) {
        this.client = client;
    }

    @Override
    public HttpResult get(String baseUrl, String pathAndQuery, String bearerToken) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + pathAndQuery))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
                    .GET();
            if (bearerToken != null) {
                builder.header("Authorization", "Bearer " + bearerToken);
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String body = response.body() == null ? "" : response.body();
            return new HttpResult(response.statusCode(), body);
        } catch (IOException e) {
            throw new SamvaadApiException(SamvaadApiException.Kind.SERVER_UNAVAILABLE, -1,
                    "Cannot reach server at " + baseUrl + ". Is it running?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SamvaadApiException(SamvaadApiException.Kind.SERVER_UNAVAILABLE, -1,
                    "Request to " + baseUrl + " was interrupted.", e);
        } catch (IllegalArgumentException e) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, -1,
                    "Invalid request: " + e.getMessage(), e);
        }
    }

    @Override
    public HttpResult post(String baseUrl, String path, String jsonBody, String bearerToken) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
                    .POST(jsonBody == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
            if (jsonBody != null) {
                builder.header("Content-Type", "application/json");
            }
            if (bearerToken != null) {
                builder.header("Authorization", "Bearer " + bearerToken);
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String body = response.body() == null ? "" : response.body();
            return new HttpResult(response.statusCode(), body);
        } catch (IOException e) {
            throw new SamvaadApiException(SamvaadApiException.Kind.SERVER_UNAVAILABLE, -1,
                    "Cannot reach server at " + baseUrl + ". Is it running?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SamvaadApiException(SamvaadApiException.Kind.SERVER_UNAVAILABLE, -1,
                    "Request to " + baseUrl + " was interrupted.", e);
        } catch (IllegalArgumentException e) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, -1,
                    "Invalid request: " + e.getMessage(), e);
        }
    }
}
