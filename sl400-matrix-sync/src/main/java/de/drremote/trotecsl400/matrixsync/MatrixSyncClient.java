package de.drremote.trotecsl400.matrixsync;

import de.drremote.trotecsl400.api.MatrixConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class MatrixSyncClient {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public String whoAmI(MatrixConfig config) throws IOException {
        String base = normalizeBaseUrl(config.homeserverBaseUrl());
        URI uri = URI.create(base + "/_matrix/client/v3/account/whoami");

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + config.accessToken())
                .GET()
                .build();

        HttpResponse<String> resp = send(request);
        if (!isSuccess(resp.statusCode())) {
            String msg = resp.body() != null ? resp.body() : "";
            throw new IOException("Matrix error " + resp.statusCode() + ": " + msg);
        }
        String body = resp.body() != null ? resp.body() : "{}";
        JsonNode json = mapper.readTree(body);
        return json.path("user_id").asText();
    }

    public SyncResult sync(MatrixConfig config, String since, int timeoutMs) throws IOException {
        String base = normalizeBaseUrl(config.homeserverBaseUrl());
        StringBuilder url = new StringBuilder(base)
                .append("/_matrix/client/v3/sync?timeout=")
                .append(timeoutMs);
        if (since != null && !since.isBlank()) {
            url.append("&since=")
               .append(URLEncoder.encode(since, StandardCharsets.UTF_8));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofMillis(Math.max(timeoutMs, 1000) + 10000))
                .header("Authorization", "Bearer " + config.accessToken())
                .GET()
                .build();

        HttpResponse<String> resp = send(request);
        if (!isSuccess(resp.statusCode())) {
            String msg = resp.body() != null ? resp.body() : "";
            throw new IOException("Matrix error " + resp.statusCode() + ": " + msg);
        }
        String body = resp.body() != null ? resp.body() : "{}";
        return new SyncResult(mapper.readTree(body));
    }

    private String normalizeBaseUrl(String base) {
        if (base == null || base.isBlank()) {
            throw new IllegalArgumentException("Homeserver URL is invalid");
        }
        String trimmed = base.trim().replaceAll("/+$", "");
        try {
            URI uri = URI.create(trimmed);
            if (uri.getScheme() == null) {
                throw new IllegalArgumentException("Homeserver URL is invalid");
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Homeserver URL is invalid");
        }
        return trimmed;
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Matrix request interrupted", e);
        }
    }

    private boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }

    public static class SyncResult {
        private final JsonNode json;

        public SyncResult(JsonNode json) {
            this.json = json;
        }

        public JsonNode json() {
            return json;
        }

        public String nextBatch() {
            String value = json.path("next_batch").asText(null);
            return (value == null || value.isBlank()) ? null : value;
        }
    }
}