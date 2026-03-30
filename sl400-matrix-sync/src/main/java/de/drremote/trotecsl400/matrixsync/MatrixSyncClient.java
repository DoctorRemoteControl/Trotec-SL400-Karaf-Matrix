package de.drremote.trotecsl400.matrixsync;

import de.drremote.trotecsl400.api.MatrixConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class MatrixSyncClient {
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public String whoAmI(MatrixConfig config) throws IOException {
        HttpUrl base = parseBaseUrl(config.homeserverBaseUrl());
        HttpUrl url = base.newBuilder()
                .addPathSegments("_matrix/client/v3/account/whoami")
                .build();

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + config.accessToken())
                .get()
                .build();

        try (Response resp = client.newCall(request).execute()) {
            if (!resp.isSuccessful()) {
                String msg = resp.body() != null ? resp.body().string() : resp.message();
                throw new IOException("Matrix error " + resp.code() + ": " + msg);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            JsonNode json = mapper.readTree(body);
            return json.path("user_id").asText();
        }
    }

    public SyncResult sync(MatrixConfig config, String since, int timeoutMs) throws IOException {
        HttpUrl base = parseBaseUrl(config.homeserverBaseUrl());
        HttpUrl.Builder url = base.newBuilder()
                .addPathSegments("_matrix/client/v3/sync")
                .addQueryParameter("timeout", String.valueOf(timeoutMs));
        if (since != null && !since.isBlank()) {
            url.addQueryParameter("since", since);
        }

        Request request = new Request.Builder()
                .url(url.build())
                .addHeader("Authorization", "Bearer " + config.accessToken())
                .get()
                .build();

        try (Response resp = client.newCall(request).execute()) {
            if (!resp.isSuccessful()) {
                String msg = resp.body() != null ? resp.body().string() : resp.message();
                throw new IOException("Matrix error " + resp.code() + ": " + msg);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            return new SyncResult(mapper.readTree(body));
        }
    }

    private HttpUrl parseBaseUrl(String base) {
        if (base == null || base.isBlank()) {
            throw new IllegalArgumentException("Homeserver URL is invalid");
        }
        String trimmed = base.trim().replaceAll("/+$", "");
        HttpUrl url = HttpUrl.parse(trimmed);
        if (url == null) {
            throw new IllegalArgumentException("Homeserver URL is invalid");
        }
        return url;
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
