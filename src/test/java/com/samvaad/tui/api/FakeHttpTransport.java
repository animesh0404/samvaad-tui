package com.samvaad.tui.api;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Test double for {@link HttpTransport} with queued responses
 * and full recording of requests. Shared across test packages.
 */
public final class FakeHttpTransport implements HttpTransport {

    public record Call(String baseUrl, String path, String body, String bearerToken) {
    }

    private final Deque<HttpResult> queued = new ArrayDeque<>();
    private final List<Call> calls = new ArrayList<>();

    public void addResult(HttpResult result) {
        queued.add(result);
    }

    public void addJson(int statusCode, String body) {
        queued.add(new HttpResult(statusCode, body));
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public Call lastCall() {
        return calls.get(calls.size() - 1);
    }

    @Override
    public HttpResult post(String baseUrl, String path, String jsonBody, String bearerToken) {
        calls.add(new Call(baseUrl, path, jsonBody, bearerToken));
        if (queued.isEmpty()) {
            throw new IllegalStateException("No queued result for POST " + path);
        }
        return queued.removeFirst();
    }

    @Override
    public HttpResult get(String baseUrl, String pathAndQuery, String bearerToken) {
        calls.add(new Call(baseUrl, pathAndQuery, null, bearerToken));
        if (queued.isEmpty()) {
            throw new IllegalStateException("No queued result for GET " + pathAndQuery);
        }
        return queued.removeFirst();
    }
}
