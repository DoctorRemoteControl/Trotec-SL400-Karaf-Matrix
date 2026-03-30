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
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

@Component(service = MatrixPublisher.class)
public class HttpMatrixPublisher implements MatrixPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(HttpMatrixPublisher.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client = new OkHttpClient();
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
        if (triggerValue == null) {
            alert.putNull("metricValue");
        } else {
            alert.put("metricValue", triggerValue);
        }
        alert.put("thresholdDb", alertConfig.thresholdDb());
        alert.put("currentDb", metrics.currentDb());
        if (metrics.maxDb1Min() == null) alert.putNull("maxDb1Min"); else alert.put("maxDb1Min", metrics.maxDb1Min());
        if (metrics.laEq1Min() == null) alert.putNull("laEq1Min"); else alert.put("laEq1Min", metrics.laEq1Min());
        if (metrics.laEq5Min() == null) alert.putNull("laEq5Min"); else alert.put("laEq5Min", metrics.laEq5Min());
        if (metrics.laEq15Min() == null) alert.putNull("laEq15Min"); else alert.put("laEq15Min", metrics.laEq15Min());
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
        if (metrics.laEq1Min() == null) metricsNode.putNull("laEq1Min"); else metricsNode.put("laEq1Min", metrics.laEq1Min());
        if (metrics.laEq5Min() == null) metricsNode.putNull("laEq5Min"); else metricsNode.put("laEq5Min", metrics.laEq5Min());
        if (metrics.laEq15Min() == null) metricsNode.putNull("laEq15Min"); else metricsNode.put("laEq15Min", metrics.laEq15Min());
        if (metrics.maxDb1Min() == null) metricsNode.putNull("maxDb1Min"); else metricsNode.put("maxDb1Min", metrics.maxDb1Min());
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
        content.put("currentDb", sample.db());
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
        HttpUrl baseUrl = parseBaseUrl(config.homeserverBaseUrl());
        HttpUrl url = baseUrl.newBuilder()
                .addPathSegments("_matrix/media/v3/upload")
                .addQueryParameter("filename", fileName)
                .build();

        RequestBody body = RequestBody.create(MediaType.parse(mimeType), file.toFile());
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + config.accessToken())
                .post(body)
                .build();

        try (Response resp = client.newCall(request).execute()) {
            if (!resp.isSuccessful()) {
                String msg = resp.body() != null ? resp.body().string() : resp.message();
                throw new IOException("Matrix upload error " + resp.code() + ": " + msg);
            }
            String jsonText = resp.body() != null ? resp.body().string() : "{}";
            JsonNode json = mapper.readTree(jsonText);
            String contentUri = json.path("content_uri").asText("");
            if (contentUri.isBlank()) {
                throw new IOException("Matrix upload missing content_uri");
            }
            return contentUri;
        }
    }

    private void sendEvent(MatrixConfig config, String roomId, String eventType, ObjectNode content)
            throws Exception {
        if (config.accessToken() == null || config.accessToken().isBlank()) {
            throw new IllegalArgumentException("Access token is missing");
        }
        if (roomId == null || roomId.isBlank()) {
            throw new IllegalArgumentException("Room ID is missing");
        }
        HttpUrl baseUrl = parseBaseUrl(config.homeserverBaseUrl());
        String txnId = System.currentTimeMillis() + "-" + UUID.randomUUID();
        HttpUrl url = baseUrl.newBuilder()
                .addPathSegments("_matrix/client/v3/rooms")
                .addPathSegment(roomId.trim())
                .addPathSegments("send")
                .addPathSegment(eventType)
                .addPathSegment(txnId)
                .build();

        RequestBody body = RequestBody.create(JSON, mapper.writeValueAsString(content));
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + config.accessToken())
                .put(body)
                .build();

        try (Response resp = client.newCall(request).execute()) {
            if (!resp.isSuccessful()) {
                String msg = resp.body() != null ? resp.body().string() : resp.message();
                throw new IOException("Matrix error " + resp.code() + ": " + msg);
            }
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
