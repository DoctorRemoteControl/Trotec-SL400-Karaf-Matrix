package de.drremote.trotecsl400.matrix;

import de.drremote.trotecsl400.api.AcousticMetrics;
import de.drremote.trotecsl400.api.AlertConfig;
import de.drremote.trotecsl400.api.MetricMode;
import de.drremote.trotecsl400.api.MatrixConfig;
import de.drremote.trotecsl400.api.MatrixPublisher;
import de.drremote.trotecsl400.api.Sl400Sample;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

@Component(service = MatrixPublisher.class)
public class HttpMatrixPublisher implements MatrixPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(HttpMatrixPublisher.class);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void sendAlert(MatrixConfig config, Sl400Sample sample, AcousticMetrics metrics,
                          AlertConfig alertConfig, String audioHint) throws Exception {
        Double triggerValue = metricValue(metrics, alertConfig.metricMode());
        String triggerText = triggerValue != null
                ? String.format(Locale.US, "%.1f", triggerValue)
                : "n/a";
        String thresholdText = String.format(Locale.US, "%.1f", alertConfig.thresholdDb());
        String currentText = String.format(Locale.US, "%.1f", metrics.currentDb());
        String max1Text = metrics.maxDb1Min() != null
                ? String.format(Locale.US, "%.1f", metrics.maxDb1Min())
                : "n/a";
        String label = metricLabel(alertConfig.metricMode());

        StringBuilder body = new StringBuilder();
        body.append("SL400 ALERT: ")
                .append(label)
                .append(" = ")
                .append(triggerText)
                .append(" dB, threshold = ")
                .append(thresholdText)
                .append(" dB. ");
        body.append("Live = ")
                .append(currentText)
                .append(" dB, Max 1 min = ")
                .append(max1Text)
                .append(" dB.");
        if (audioHint != null && !audioHint.isBlank()) {
            body.append(" Hint: ").append(audioHint);
        }

        ObjectNode alert = mapper.createObjectNode();
        alert.put("timestampMs", sample.timestampMs());
        alert.put("metricMode", alertConfig.metricMode().name());
        alert.put("metricLabel", label);
        Integer metricTenths = dbTenths(triggerValue);
        Integer thresholdTenths = dbTenths(alertConfig.thresholdDb());
        Integer currentTenths = dbTenths(metrics.currentDb());
        Integer max1Tenths = dbTenths(metrics.maxDb1Min());
        Integer laeq1Tenths = dbTenths(metrics.laEq1Min());
        Integer laeq5Tenths = dbTenths(metrics.laEq5Min());
        Integer laeq15Tenths = dbTenths(metrics.laEq15Min());
        if (metricTenths == null) alert.putNull("metricValueTenths"); else alert.put("metricValueTenths", metricTenths);
        if (thresholdTenths == null) alert.putNull("thresholdDbTenths"); else alert.put("thresholdDbTenths", thresholdTenths);
        if (currentTenths == null) alert.putNull("currentDbTenths"); else alert.put("currentDbTenths", currentTenths);
        if (max1Tenths == null) alert.putNull("maxDb1MinTenths"); else alert.put("maxDb1MinTenths", max1Tenths);
        if (laeq1Tenths == null) alert.putNull("laEq1MinTenths"); else alert.put("laEq1MinTenths", laeq1Tenths);
        if (laeq5Tenths == null) alert.putNull("laEq5MinTenths"); else alert.put("laEq5MinTenths", laeq5Tenths);
        if (laeq15Tenths == null) alert.putNull("laEq15MinTenths"); else alert.put("laEq15MinTenths", laeq15Tenths);
        alert.put("coverage1MinMs", metrics.coverage1MinMs());
        alert.put("coverage5MinMs", metrics.coverage5MinMs());
        alert.put("coverage15MinMs", metrics.coverage15MinMs());
        alert.put("timeAboveThresholdMs1Min", metrics.timeAboveThresholdMs1Min());
        if (audioHint == null) alert.putNull("audioHint"); else alert.put("audioHint", audioHint);

        ObjectNode content = mapper.createObjectNode();
        content.put("msgtype", "m.text");
        content.put("body", body.toString());
        addEnvelope(content, config, "alert", sample.timestampMs(), String.valueOf(sample.timestampMs()));
        content.set("sl400_alert", alert);

        sendEvent(config, config.roomId(), "m.room.message", content);
    }

    @Override
    public void sendStatus(MatrixConfig config, Sl400Sample sample, AcousticMetrics metrics,
                           boolean serialOnline, boolean audioRunning) throws Exception {
        ObjectNode metricsNode = mapper.createObjectNode();
        Integer laeq1Tenths = dbTenths(metrics.laEq1Min());
        Integer laeq5Tenths = dbTenths(metrics.laEq5Min());
        Integer laeq15Tenths = dbTenths(metrics.laEq15Min());
        Integer max1Tenths = dbTenths(metrics.maxDb1Min());
        if (laeq1Tenths == null) metricsNode.putNull("laEq1MinTenths"); else metricsNode.put("laEq1MinTenths", laeq1Tenths);
        if (laeq5Tenths == null) metricsNode.putNull("laEq5MinTenths"); else metricsNode.put("laEq5MinTenths", laeq5Tenths);
        if (laeq15Tenths == null) metricsNode.putNull("laEq15MinTenths"); else metricsNode.put("laEq15MinTenths", laeq15Tenths);
        if (max1Tenths == null) metricsNode.putNull("maxDb1MinTenths"); else metricsNode.put("maxDb1MinTenths", max1Tenths);
        metricsNode.put("coverage1MinMs", metrics.coverage1MinMs());
        metricsNode.put("coverage5MinMs", metrics.coverage5MinMs());
        metricsNode.put("coverage15MinMs", metrics.coverage15MinMs());
        metricsNode.put("timeAboveThresholdMs1Min", metrics.timeAboveThresholdMs1Min());

        ObjectNode content = mapper.createObjectNode();
        content.put("msgtype", "m.text");
        content.put("body", "SL400 status");
        addEnvelope(content, config, "status", System.currentTimeMillis(), null);
        content.put("serialOnline", serialOnline);
        content.put("audioRunning", audioRunning);
        content.put("lastSampleTs", sample.timestampMs());
        Integer currentTenths = dbTenths(sample.db());
        if (currentTenths == null) content.putNull("currentDbTenths"); else content.put("currentDbTenths", currentTenths);
        content.set("metrics", metricsNode);

        sendEvent(config, config.roomId(), "m.room.message", content);
    }

    @Override
    public void sendText(MatrixConfig config, String roomId, String body) throws Exception {
        ObjectNode content = mapper.createObjectNode();
        content.put("msgtype", "m.text");
        content.put("body", body);
        sendEvent(config, roomId, "m.room.message", content);
    }

    @Override
    public void sendFile(MatrixConfig config, String roomId, Path file, String body, String mimeType)
            throws Exception {
        String mxcUrl = uploadMedia(config, file, mimeType, file.getFileName().toString());
        ObjectNode info = mapper.createObjectNode();
        info.put("mimetype", mimeType);
        info.put("size", file.toFile().length());
        ObjectNode content = mapper.createObjectNode();
        content.put("msgtype", "m.file");
        content.put("body", body);
        content.put("filename", file.getFileName().toString());
        content.put("url", mxcUrl);
        content.set("info", info);
        sendEvent(config, roomId, "m.room.message", content);
    }

    @Override
    public void sendImage(MatrixConfig config, String roomId, Path file, String body, String mimeType)
            throws Exception {
        String mxcUrl = uploadMedia(config, file, mimeType, file.getFileName().toString());
        ObjectNode info = mapper.createObjectNode();
        info.put("mimetype", mimeType);
        info.put("size", file.toFile().length());
        ObjectNode content = mapper.createObjectNode();
        content.put("msgtype", "m.image");
        content.put("body", body);
        content.put("filename", file.getFileName().toString());
        content.put("url", mxcUrl);
        content.set("info", info);
        sendEvent(config, roomId, "m.room.message", content);
    }

    @Override
    public String sendAudio(MatrixConfig config, String roomId, Path file, String body, String mimeType)
            throws Exception {
        String mxcUrl = uploadMedia(config, file, mimeType, file.getFileName().toString());
        ObjectNode info = mapper.createObjectNode();
        info.put("mimetype", mimeType);
        info.put("size", file.toFile().length());
        ObjectNode content = mapper.createObjectNode();
        content.put("msgtype", "m.audio");
        content.put("body", body);
        content.put("filename", file.getFileName().toString());
        content.put("url", mxcUrl);
        content.set("info", info);
        sendEvent(config, roomId, "m.room.message", content);
        return mxcUrl;
    }

    @Override
    public void sendAudioByMxcUrl(MatrixConfig config, String roomId, String mxcUrl,
                                  String body, String mimeType, String fileName, Long size)
            throws Exception {
        ObjectNode info = mapper.createObjectNode();
        if (mimeType != null && !mimeType.isBlank()) {
            info.put("mimetype", mimeType);
        }
        if (size != null && size > 0) {
            info.put("size", size);
        }
        ObjectNode content = mapper.createObjectNode();
        content.put("msgtype", "m.audio");
        content.put("body", body);
        content.put("filename", fileName == null ? "audio" : fileName);
        content.put("url", mxcUrl);
        if (info.size() > 0) {
            content.set("info", info);
        }
        sendEvent(config, roomId, "m.room.message", content);
    }

    @Override
    public String sendClip(MatrixConfig config, String roomId, String incidentId, long timestampMs,
                           Path file, String body, String mimeType) throws Exception {
        String mxcUrl = uploadMedia(config, file, mimeType, file.getFileName().toString());
        ObjectNode info = mapper.createObjectNode();
        info.put("mimetype", mimeType);
        info.put("size", file.toFile().length());
        ObjectNode content = mapper.createObjectNode();
        content.put("msgtype", "m.audio");
        content.put("body", body);
        content.put("filename", file.getFileName().toString());
        content.put("url", mxcUrl);
        content.set("info", info);
        addEnvelope(content, config, "clip", timestampMs, incidentId);
        sendEvent(config, roomId, "m.room.message", content);
        return mxcUrl;
    }

    @Override
    public void sendClipByMxcUrl(MatrixConfig config, String roomId, String incidentId, long timestampMs,
                                 String mxcUrl, String body, String mimeType, String fileName, Long size)
            throws Exception {
        ObjectNode info = mapper.createObjectNode();
        if (mimeType != null && !mimeType.isBlank()) {
            info.put("mimetype", mimeType);
        }
        if (size != null && size > 0) {
            info.put("size", size);
        }
        ObjectNode content = mapper.createObjectNode();
        content.put("msgtype", "m.audio");
        content.put("body", body);
        content.put("filename", fileName == null ? "audio" : fileName);
        content.put("url", mxcUrl);
        if (info.size() > 0) {
            content.set("info", info);
        }
        addEnvelope(content, config, "clip", timestampMs, incidentId);
        sendEvent(config, roomId, "m.room.message", content);
    }

    private String uploadMedia(MatrixConfig config, Path file, String mimeType, String fileName)
            throws Exception {
        if (config.accessToken() == null || config.accessToken().isBlank()) {
            throw new IllegalArgumentException("Access token is missing");
        }
        String baseUrl = normalizeBaseUrl(config.homeserverBaseUrl());
        String url = baseUrl + "/_matrix/media/v3/upload?filename=" + encodeQuery(fileName);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + config.accessToken())
                .header("Content-Type", mimeType == null || mimeType.isBlank()
                        ? "application/octet-stream" : mimeType)
                .POST(HttpRequest.BodyPublishers.ofFile(file))
                .build();

        HttpResponse<String> resp = send(request);
        if (!isSuccess(resp.statusCode())) {
            String msg = resp.body() != null ? resp.body() : "";
            throw new IOException("Matrix upload error " + resp.statusCode() + ": " + msg);
        }
        String jsonText = resp.body() != null ? resp.body() : "{}";
        JsonNode json = mapper.readTree(jsonText);
        String contentUri = json.path("content_uri").asText("");
        if (contentUri.isBlank()) {
            throw new IOException("Matrix upload missing content_uri");
        }
        return contentUri;
    }

    private void sendEvent(MatrixConfig config, String roomId, String eventType, ObjectNode content)
            throws Exception {
        if (config.accessToken() == null || config.accessToken().isBlank()) {
            throw new IllegalArgumentException("Access token is missing");
        }
        if (roomId == null || roomId.isBlank()) {
            throw new IllegalArgumentException("Room ID is missing");
        }
        String baseUrl = normalizeBaseUrl(config.homeserverBaseUrl());
        String txnId = System.currentTimeMillis() + "-" + UUID.randomUUID();
        String url = baseUrl + "/_matrix/client/v3/rooms/" + encodePath(roomId.trim())
                + "/send/" + encodePath(eventType)
                + "/" + encodePath(txnId);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + config.accessToken())
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(content)))
                .build();

        HttpResponse<String> resp = send(request);
        if (!isSuccess(resp.statusCode())) {
            String msg = resp.body() != null ? resp.body() : "";
            throw new IOException("Matrix error " + resp.statusCode() + ": " + msg);
        }
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

    private String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private Double metricValue(AcousticMetrics metrics, MetricMode mode) {
        return switch (mode) {
            case LIVE -> metrics.currentDb();
            case LAEQ_1_MIN -> metrics.laEq1Min();
            case LAEQ_5_MIN -> metrics.laEq5Min();
            case LAEQ_15_MIN -> metrics.laEq15Min();
            case MAX_1_MIN -> metrics.maxDb1Min();
        };
    }

    private String metricLabel(MetricMode mode) {
        return switch (mode) {
            case LIVE -> "Live";
            case LAEQ_1_MIN -> "LAeq 1 min";
            case LAEQ_5_MIN -> "LAeq 5 min";
            case LAEQ_15_MIN -> "LAeq 15 min";
            case MAX_1_MIN -> "Max 1 min";
        };
    }

    private Integer dbTenths(Double value) {
        if (value == null) {
            return null;
        }
        return (int) Math.round(value * 10.0);
    }

    private void addEnvelope(ObjectNode content, MatrixConfig config,
                             String type, long timestampMs, String incidentId) {
        content.put("sl400_version", 1);
        content.put("sl400_type", type);
        content.put("sl400_device_id", config.deviceId());
        content.put("timestampMs", timestampMs);
        if (incidentId != null) {
            content.put("incidentId", incidentId);
        }
    }
}
