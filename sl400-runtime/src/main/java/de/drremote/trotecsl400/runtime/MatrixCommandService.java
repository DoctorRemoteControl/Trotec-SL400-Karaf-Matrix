package de.drremote.trotecsl400.runtime;

import de.drremote.trotecsl400.api.AlertConfig;
import de.drremote.trotecsl400.api.AlertConfigService;
import de.drremote.trotecsl400.api.IncidentRecord;
import de.drremote.trotecsl400.api.IncidentRepository;
import de.drremote.trotecsl400.api.MatrixConfig;
import de.drremote.trotecsl400.api.MatrixConfigService;
import de.drremote.trotecsl400.api.MatrixPublisher;
import de.drremote.trotecsl400.matrixsync.MatrixCommandProcessor;
import de.drremote.trotecsl400.matrixsync.MatrixSyncClient;
import de.drremote.trotecsl400.report.JsonExporter;
import de.drremote.trotecsl400.report.AudioSpectrumAnalyzer;
import de.drremote.trotecsl400.report.AudioSpectrumRenderer;
import de.drremote.trotecsl400.report.IncidentGraphRenderer;
import de.drremote.trotecsl400.report.SpectrumResult;
import de.drremote.trotecsl400.report.SummaryFormatter;
import com.fasterxml.jackson.databind.JsonNode;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

@Component(immediate = true)
public class MatrixCommandService {
    private static final Logger LOG = LoggerFactory.getLogger(MatrixCommandService.class);

    @Reference
    private MatrixConfigService matrixConfigService;

    @Reference
    private AlertConfigService alertConfigService;

    @Reference
    private AlertConfigAdminService alertConfigAdminService;

    @Reference
    private MatrixPublisher matrixPublisher;

    @Reference
    private IncidentRepository incidentRepository;

    @Reference
    private AudioCaptureService audioCaptureService;

    private final MatrixSyncClient syncClient = new MatrixSyncClient();
    private final MatrixCommandProcessor commandProcessor = new MatrixCommandProcessor();

    private volatile boolean running = false;
    private volatile Thread worker;
    private volatile String sinceToken;
    private volatile String ownUserId;
    private volatile boolean initialized = false;
    private volatile Path syncTokenFile;
    private volatile String syncIdentity;

    @Activate
    void activate() {
        initSyncTokenFile();
        start();
    }

    @Deactivate
    void deactivate() {
        stop();
    }

    private void start() {
        if (running) return;
        running = true;
        worker = new Thread(this::runLoop, "sl400-matrix-sync");
        worker.setDaemon(true);
        worker.start();
    }

    private void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void initSyncTokenFile() {
        try {
            String base = System.getProperty("karaf.data", "data");
            Path baseDir = Path.of(base, "sl400");
            Files.createDirectories(baseDir);
            syncTokenFile = baseDir.resolve("matrix-sync-token.txt");
            if (Files.exists(syncTokenFile)) {
                String token = Files.readString(syncTokenFile).trim();
                if (!token.isBlank()) {
                    sinceToken = token;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to load sync token: {}", e.getMessage());
        }
    }

    private void persistSinceToken(String token) {
        if (syncTokenFile == null || token == null || token.isBlank()) {
            return;
        }
        try {
            Files.writeString(
                    syncTokenFile,
                    token,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (Exception e) {
            LOG.debug("Failed to persist sync token: {}", e.getMessage());
        }
    }

    private void clearSyncTokenFile() {
        if (syncTokenFile == null) {
            return;
        }
        try {
            Files.writeString(
                    syncTokenFile,
                    "",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (Exception e) {
            LOG.debug("Failed to clear sync token file: {}", e.getMessage());
        }
    }

    private void runLoop() {
        while (running) {
            try {
                MatrixConfig cfg = matrixConfigService.getConfig();
                AlertConfig alertCfg = alertConfigService.getConfig();
                if (!shouldRun(cfg, alertCfg)) {
                    sleepQuiet(5000);
                    continue;
                }
                String currentIdentity = buildSyncIdentity(cfg, alertCfg);
                if (syncIdentity == null || !syncIdentity.equals(currentIdentity)) {
                    syncIdentity = currentIdentity;
                    ownUserId = null;
                    initialized = false;
                    sinceToken = null;
                    clearSyncTokenFile();
                }
                if (ownUserId == null) {
                    ownUserId = syncClient.whoAmI(cfg);
                }
                MatrixSyncClient.SyncResult result = syncClient.sync(cfg, sinceToken, 30000);
                String next = result.nextBatch();
                boolean hadToken = sinceToken != null && !sinceToken.isBlank();
                if (!initialized && !hadToken) {
                    sinceToken = next;
                    persistSinceToken(sinceToken);
                    initialized = true;
                    continue; // first sync without token: skip backlog
                }
                handleSync(result.json(), alertCfg, cfg);
                sinceToken = next;
                persistSinceToken(sinceToken);
                initialized = true;
            } catch (Exception e) {
                LOG.warn("Matrix sync error: {}", e.getMessage());
                sleepQuiet(5000);
            }
        }
    }

    private boolean shouldRun(MatrixConfig cfg, AlertConfig alertCfg) {
        return cfg.enabled()
                && cfg.homeserverBaseUrl() != null && !cfg.homeserverBaseUrl().isBlank()
                && cfg.accessToken() != null && !cfg.accessToken().isBlank()
                && alertCfg.commandRoomId() != null && !alertCfg.commandRoomId().isBlank();
    }

    private String buildSyncIdentity(MatrixConfig cfg, AlertConfig alertCfg) {
        return (cfg.homeserverBaseUrl() == null ? "" : cfg.homeserverBaseUrl())
                + "|" + (cfg.accessToken() == null ? "" : cfg.accessToken())
                + "|" + (alertCfg.commandRoomId() == null ? "" : alertCfg.commandRoomId());
    }

    private void handleSync(JsonNode json, AlertConfig alertCfg, MatrixConfig matrixCfg) {
        JsonNode rooms = json.path("rooms");
        if (!rooms.isObject()) return;
        JsonNode join = rooms.path("join");
        if (!join.isObject()) return;

        Iterator<String> roomIds = join.fieldNames();
        while (roomIds.hasNext()) {
            String roomId = roomIds.next();
            if (alertCfg.commandRoomId() != null && !alertCfg.commandRoomId().isBlank()
                    && !roomId.equals(alertCfg.commandRoomId())) {
                continue;
            }
            JsonNode room = join.path(roomId);
            JsonNode timeline = room.path("timeline");
            JsonNode events = timeline.path("events");
            if (!events.isArray()) continue;

            for (JsonNode ev : events) {
                if (!"m.room.message".equals(ev.path("type").asText())) continue;
                String sender = ev.path("sender").asText(null);
                if (ownUserId != null && ownUserId.equals(sender)) continue;
                JsonNode content = ev.path("content");
                if (!"m.text".equals(content.path("msgtype").asText())) continue;
                String body = content.path("body").asText("");
                if (body.isBlank()) continue;

                handleMessage(body, sender, roomId, matrixCfg);
            }
        }
    }

    private void handleMessage(String body, String sender, String roomId, MatrixConfig matrixCfg) {
        AlertConfig current = alertConfigService.getConfig();
        MatrixCommandProcessor.CommandResult result =
                commandProcessor.process(body, sender, roomId, current);
        if (result == null) return;

        if (!result.updatedConfig.equals(current)) {
            applyAlertConfigChanges(current, result.updatedConfig);
        }

        if (result.action != null) {
            if (result.responseMessage != null && !result.responseMessage.isBlank()) {
                sendTextSafely(matrixCfg, roomId, result.responseMessage);
            }
            handleAction(result.action, matrixCfg, roomId);
        } else {
            sendTextSafely(matrixCfg, roomId, result.responseMessage);
        }
    }

    private void applyAlertConfigChanges(AlertConfig oldCfg, AlertConfig newCfg) {
        boolean ok1 = alertConfigAdminService.updateOperatorConfig(newCfg);
        if (!ok1) {
            LOG.warn("Failed to persist operator config changes via ConfigAdmin");
        }
        if (!oldCfg.allowedSenders().equals(newCfg.allowedSenders())) {
            String csv = String.join(",", newCfg.allowedSenders());
            boolean ok2 = alertConfigAdminService.updateAllowedSendersCsv(csv);
            if (!ok2) {
                LOG.warn("Failed to persist allowedSendersCsv via ConfigAdmin");
            }
        }
        if (!oldCfg.commandRoomId().equals(newCfg.commandRoomId())) {
            boolean ok3 = alertConfigAdminService.updateCommandRoomId(newCfg.commandRoomId());
            if (!ok3) {
                LOG.warn("Failed to persist commandRoomId via ConfigAdmin");
            }
        }
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleAction(MatrixCommandProcessor.CommandAction action,
                              MatrixConfig matrixCfg, String roomId) {
        try {
            AlertConfig alertCfg = alertConfigService.getConfig();
            switch (action) {
                case MatrixCommandProcessor.IncidentsSince a ->
                        respondWithIncidents(matrixCfg, roomId,
                                incidentRepository.getIncidentsSince(a.durationMs()),
                                "since " + a.label());
                case MatrixCommandProcessor.IncidentsBetween a ->
                        respondWithIncidents(matrixCfg, roomId,
                                incidentRepository.getIncidentsBetween(a.startMs(), a.endMs()),
                                "between " + a.label());
                case MatrixCommandProcessor.IncidentsToday a ->
                        respondWithIncidents(matrixCfg, roomId,
                                incidentsForToday(),
                                "today");
                case MatrixCommandProcessor.IncidentsYesterday a ->
                        respondWithIncidents(matrixCfg, roomId,
                                incidentsForYesterday(),
                                "yesterday");

                case MatrixCommandProcessor.SummarySince a ->
                        sendTextSafely(matrixCfg, roomId,
                                SummaryFormatter.buildSummaryMessage(
                                        incidentRepository.getIncidentsSince(a.durationMs()),
                                        "since " + a.label(),
                                        null
                                ));
                case MatrixCommandProcessor.SummaryToday a ->
                        sendTextSafely(matrixCfg, roomId,
                                SummaryFormatter.buildSummaryMessage(
                                        incidentsForToday(),
                                        "today",
                                        null
                                ));
                case MatrixCommandProcessor.SummaryYesterday a ->
                        sendTextSafely(matrixCfg, roomId,
                                SummaryFormatter.buildSummaryMessage(
                                        incidentsForYesterday(),
                                        "yesterday",
                                        null
                                ));

                case MatrixCommandProcessor.JsonSince a ->
                        sendJson(matrixCfg, roomId,
                                incidentRepository.getIncidentsSince(a.durationMs()),
                                "since_" + a.label());
                case MatrixCommandProcessor.JsonToday a ->
                        sendJson(matrixCfg, roomId,
                                incidentsForToday(),
                                "today");
                case MatrixCommandProcessor.JsonYesterday a ->
                        sendJson(matrixCfg, roomId,
                                incidentsForYesterday(),
                                "yesterday");

                case MatrixCommandProcessor.ReportNow a ->
                        sendReport(matrixCfg, roomId, incidentsForToday(), "report_now", alertCfg);
                case MatrixCommandProcessor.ReportToday a ->
                        sendReport(matrixCfg, roomId, incidentsForToday(), "today", alertCfg);
                case MatrixCommandProcessor.ReportYesterday a ->
                        sendReport(matrixCfg, roomId, incidentsForYesterday(), "yesterday", alertCfg);
                case MatrixCommandProcessor.ReportSince a ->
                        sendReport(matrixCfg, roomId,
                                incidentRepository.getIncidentsSince(a.durationMs()),
                                "since_" + a.label(),
                                alertCfg);

                case MatrixCommandProcessor.GraphSince a ->
                        sendGraph(matrixCfg, roomId,
                                incidentRepository.getIncidentsSince(a.durationMs()),
                                "since_" + a.label(),
                                alertCfg);
                case MatrixCommandProcessor.GraphToday a ->
                        sendGraph(matrixCfg, roomId,
                                incidentsForToday(),
                                "today",
                                alertCfg);
                case MatrixCommandProcessor.GraphYesterday a ->
                        sendGraph(matrixCfg, roomId,
                                incidentsForYesterday(),
                                "yesterday",
                                alertCfg);

                case MatrixCommandProcessor.ClipLast a ->
                        sendLastClip(matrixCfg, roomId);
                case MatrixCommandProcessor.ClipIncident a ->
                        sendClipForIncident(matrixCfg, roomId, a.incidentId());
                case MatrixCommandProcessor.ClipsSince a ->
                        sendClipsSince(matrixCfg, roomId, a.durationMs(), a.label());

                case MatrixCommandProcessor.HintLast a ->
                        sendLastHint(matrixCfg, roomId);
                case MatrixCommandProcessor.HintIncident a ->
                        sendHintForIncident(matrixCfg, roomId, a.incidentId());

                case MatrixCommandProcessor.FftLast a ->
                        sendLastFft(matrixCfg, roomId);
                case MatrixCommandProcessor.FftIncident a ->
                        sendFftForIncident(matrixCfg, roomId, a.incidentId());

                case MatrixCommandProcessor.AudioStart a ->
                        sendTextSafely(matrixCfg, roomId,
                                audioCaptureService.startCapture()
                                        ? "SL400: audio capture started."
                                        : "SL400: audio capture failed to start.");
                case MatrixCommandProcessor.AudioStop a ->
                        { audioCaptureService.stopCapture();
                        sendTextSafely(matrixCfg, roomId, "SL400: audio capture stopped."); }
                case MatrixCommandProcessor.AudioStatus a ->
                        sendTextSafely(matrixCfg, roomId, audioCaptureService.status());
            }
        } catch (Exception e) {
            LOG.warn("Matrix action failed: {}", e.getMessage());
            sendTextSafely(matrixCfg, roomId, "SL400: action failed: " + e.getMessage());
        }
    }

    private List<IncidentRecord> incidentsForToday() throws Exception {
        long[] range = dayRange(LocalDate.now());
        return incidentRepository.getIncidentsBetween(range[0], range[1]);
    }

    private List<IncidentRecord> incidentsForYesterday() throws Exception {
        long[] range = dayRange(LocalDate.now().minusDays(1));
        return incidentRepository.getIncidentsBetween(range[0], range[1]);
    }

    private long[] dayRange(LocalDate date) {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime start = date.atStartOfDay(zone);
        ZonedDateTime end = start.plusDays(1);
        return new long[]{start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli()};
    }

    private void respondWithIncidents(MatrixConfig matrixCfg, String roomId,
                                      List<IncidentRecord> incidents, String label) {
        if (incidents == null || incidents.isEmpty()) {
            sendTextSafely(matrixCfg, roomId, "SL400: no incidents " + label + ".");
            return;
        }
        List<IncidentRecord> sorted = new ArrayList<>(incidents);
        sorted.sort(Comparator.comparingLong(IncidentRecord::timestampMs).reversed());
        int limit = Math.min(10, sorted.size());
        StringBuilder sb = new StringBuilder();
        sb.append("SL400: ").append(sorted.size()).append(" incidents ").append(label).append(":\n");
        for (int i = 0; i < limit; i++) {
            IncidentRecord r = sorted.get(i);
            sb.append(formatIncidentLine(r)).append("\n");
        }
        if (sorted.size() > limit) {
            sb.append("... and ").append(sorted.size() - limit).append(" more.");
        }
        sendTextSafely(matrixCfg, roomId, sb.toString().trim());
    }

    private String formatIncidentLine(IncidentRecord r) {
        String time = formatTimestamp(r.timestampMs());
        String metric = r.metricValue() == null
                ? "n/a"
                : String.format(java.util.Locale.US, "%.1f dB", r.metricValue());
        String mode = r.metricMode() == null ? "unknown" : r.metricMode();
        String room = r.roomId() == null ? "" : r.roomId();
        String id = r.incidentId() == null ? "-" : r.incidentId();
        String clip = r.clipPath() != null && !r.clipPath().isBlank() ? "clip=yes" : "clip=no";
        return time + " | id=" + id + " | " + metric + " (" + mode + ") | " + clip + " | " + room;
    }

    private String formatTimestamp(long ts) {
        Instant instant = Instant.ofEpochMilli(ts);
        LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        return ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private void sendReport(MatrixConfig matrixCfg, String roomId,
                            List<IncidentRecord> incidents, String label,
                            AlertConfig alertCfg) {
        String summary = SummaryFormatter.buildSummaryMessage(incidents, label, null);
        sendTextSafely(matrixCfg, roomId, summary);

        if (alertCfg.dailyReportJsonEnabled()) {
            sendJson(matrixCfg, roomId, incidents, label);
        }
        if (alertCfg.dailyReportGraphEnabled()) {
            sendGraph(matrixCfg, roomId, incidents, label, alertCfg);
        }
    }

    private void sendJson(MatrixConfig matrixCfg, String roomId,
                          List<IncidentRecord> incidents, String label) {
        Path json = null;
        try {
            json = JsonExporter.writeIncidentsJson(incidents, label);
            matrixPublisher.sendFile(
                    matrixCfg,
                    roomId,
                    json,
                    "SL400 incidents JSON (" + label + ")",
                    "application/json"
            );
        } catch (Exception e) {
            LOG.warn("Failed to send JSON export: {}", e.getMessage());
            sendTextSafely(matrixCfg, roomId, "SL400: JSON export failed: " + e.getMessage());
        } finally {
            if (json != null) {
                try {
                    Files.deleteIfExists(json);
                } catch (Exception e) {
                    LOG.debug("Failed to delete temp JSON {}", json, e);
                }
            }
        }
    }

    private void sendGraph(MatrixConfig matrixCfg, String roomId,
                           List<IncidentRecord> incidents, String label,
                           AlertConfig alertCfg) {
        Path png = null;
        try {
            png = IncidentGraphRenderer.renderGraph(incidents, alertCfg, label);
            matrixPublisher.sendImage(
                    matrixCfg,
                    roomId,
                    png,
                    "SL400 incidents graph (" + label + ")",
                    "image/png"
            );
        } catch (Exception e) {
            LOG.warn("Failed to send graph export: {}", e.getMessage());
            sendTextSafely(matrixCfg, roomId, "SL400: graph export failed: " + e.getMessage());
        } finally {
            if (png != null) {
                try {
                    Files.deleteIfExists(png);
                } catch (Exception e) {
                    LOG.debug("Failed to delete temp graph {}", png, e);
                }
            }
        }
    }

    private void sendLastClip(MatrixConfig matrixCfg, String roomId) throws Exception {
        IncidentRecord last = incidentRepository.getLastClipIncident();
        if (last == null) {
            sendTextSafely(matrixCfg, roomId, "SL400: no clips available.");
            return;
        }
        sendClip(matrixCfg, roomId, last, "last clip");
    }

    private void sendClipForIncident(MatrixConfig matrixCfg, String roomId, String incidentId)
            throws Exception {
        IncidentRecord record = incidentRepository.getIncidentById(incidentId);
        if (record == null) {
            sendTextSafely(matrixCfg, roomId, "SL400: incident not found: " + incidentId);
            return;
        }
        if (record.clipPath() == null || record.clipPath().isBlank()) {
            sendTextSafely(matrixCfg, roomId, "SL400: incident has no clip: " + incidentId);
            return;
        }
        sendClip(matrixCfg, roomId, record, "incident " + incidentId);
    }

    private void sendClipsSince(MatrixConfig matrixCfg, String roomId,
                                long durationMs, String label) throws Exception {
        List<IncidentRecord> clips = incidentRepository.getClipsSince(durationMs);
        if (clips.isEmpty()) {
            sendTextSafely(matrixCfg, roomId, "SL400: no clips " + label + ".");
            return;
        }
        clips.sort(Comparator.comparingLong(IncidentRecord::timestampMs).reversed());
        int limit = Math.min(5, clips.size());
        for (int i = 0; i < limit; i++) {
            sendClip(matrixCfg, roomId, clips.get(i), "clip " + (i + 1) + " of " + limit);
        }
        if (clips.size() > limit) {
            sendTextSafely(matrixCfg, roomId,
                    "SL400: " + (clips.size() - limit) + " more clips not sent.");
        }
    }

    private void sendClip(MatrixConfig matrixCfg, String roomId,
                          IncidentRecord record, String label) throws Exception {
        String clipPath = record.clipPath();
        String body = "SL400 clip (" + label + ") id=" + record.incidentId();
        String mxcUrl = record.mxcUrl();
        String mime = detectAudioMime(clipPath);
        long clipTs = record.timestampMs();
        boolean sent = false;

        if (record.clipUploaded() && mxcUrl != null && !mxcUrl.isBlank()) {
            String fileName = clipPath == null || clipPath.isBlank()
                    ? "sl400_clip_" + record.incidentId() + ".wav"
                    : Path.of(clipPath).getFileName().toString();
            matrixPublisher.sendClipByMxcUrl(matrixCfg, roomId, record.incidentId(), clipTs,
                    mxcUrl, body, mime, fileName, null);
            sent = true;
        }
        if (!sent) {
            if (clipPath == null || clipPath.isBlank()) {
                sendTextSafely(matrixCfg, roomId, "SL400: clip missing (" + label + ").");
                return;
            }
            Path file = Path.of(clipPath);
            if (!Files.exists(file)) {
                if (mxcUrl != null && !mxcUrl.isBlank()) {
                    String fileName = file.getFileName().toString();
                    matrixPublisher.sendClipByMxcUrl(matrixCfg, roomId, record.incidentId(), clipTs,
                            mxcUrl, body, mime, fileName, null);
                    sent = true;
                } else {
                    sendTextSafely(matrixCfg, roomId, "SL400: clip file not found (" + label + ").");
                    return;
                }
            } else {
                String uploadedUrl = matrixPublisher.sendClip(matrixCfg, roomId, record.incidentId(), clipTs,
                        file, body, mime);
                incidentRepository.markUploaded(record.incidentId(), uploadedUrl);
                sent = true;
            }
        }

        if (sent && record.audioHint() != null && !record.audioHint().isBlank()) {
            sendTextSafely(matrixCfg, roomId,
                    "SL400 audio hint for incident " + record.incidentId() + ": " + record.audioHint());
        }
    }

    private String detectAudioMime(String clipPath) {
        if (clipPath == null || clipPath.isBlank()) {
            return "application/octet-stream";
        }
        String name = Path.of(clipPath).getFileName().toString().toLowerCase();
        if (name.endsWith(".wav")) return "audio/wav";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".flac")) return "audio/flac";
        if (name.endsWith(".ogg")) return "audio/ogg";
        return "application/octet-stream";
    }

    private void sendTextSafely(MatrixConfig matrixCfg, String roomId, String message) {
        try {
            matrixPublisher.sendText(matrixCfg, roomId, message);
        } catch (Exception e) {
            LOG.warn("Failed to send matrix response: {}", e.getMessage());
        }
    }

    private void sendLastHint(MatrixConfig matrixCfg, String roomId) throws Exception {
        IncidentRecord last = incidentRepository.getLastClipIncident();
        if (last == null) {
            sendTextSafely(matrixCfg, roomId, "SL400: no incidents with clip available.");
            return;
        }
        sendHintRecord(matrixCfg, roomId, last);
    }

    private void sendHintForIncident(MatrixConfig matrixCfg, String roomId, String incidentId)
            throws Exception {
        IncidentRecord record = incidentRepository.getIncidentById(incidentId);
        if (record == null) {
            sendTextSafely(matrixCfg, roomId, "SL400: incident not found: " + incidentId);
            return;
        }
        sendHintRecord(matrixCfg, roomId, record);
    }

    private void sendHintRecord(MatrixConfig matrixCfg, String roomId, IncidentRecord record) {
        String hint = record.audioHint();
        if (hint == null || hint.isBlank()) {
            sendTextSafely(matrixCfg, roomId,
                    "SL400: no audio hint available yet for incident " + record.incidentId() + ".");
            return;
        }
        sendTextSafely(matrixCfg, roomId,
                "SL400 audio hint for incident " + record.incidentId() + ": " + hint);
    }

    private void sendLastFft(MatrixConfig matrixCfg, String roomId) throws Exception {
        IncidentRecord last = incidentRepository.getLastClipIncident();
        if (hasLocalClip(last)) {
            sendFftRecord(matrixCfg, roomId, last, "last clip");
            return;
        }

        List<IncidentRecord> clips = incidentRepository.getClipsSince(3650L * 86_400_000L);
        clips.sort(Comparator.comparingLong(IncidentRecord::timestampMs).reversed());
        for (IncidentRecord record : clips) {
            if (hasLocalClip(record)) {
                sendFftRecord(matrixCfg, roomId, record, "last local clip");
                return;
            }
        }

        sendTextSafely(matrixCfg, roomId, "SL400: no local clips available for FFT.");
    }

    private void sendFftForIncident(MatrixConfig matrixCfg, String roomId, String incidentId)
            throws Exception {
        IncidentRecord record = incidentRepository.getIncidentById(incidentId);
        if (record == null) {
            sendTextSafely(matrixCfg, roomId, "SL400: incident not found: " + incidentId);
            return;
        }
        sendFftRecord(matrixCfg, roomId, record, "incident " + incidentId);
    }

    private void sendFftRecord(MatrixConfig matrixCfg, String roomId,
                               IncidentRecord record, String label) throws Exception {
        if (!hasLocalClip(record)) {
            sendTextSafely(matrixCfg, roomId, "SL400: no clip available for " + label + ".");
            return;
        }
        Path wav = Path.of(record.clipPath());

        Path png = null;
        try {
            SpectrumResult spectrum = AudioSpectrumAnalyzer.analyze(wav);
            png = AudioSpectrumRenderer.renderSpectrumPng(
                    spectrum,
                    "SL400 FFT (" + label + ") id=" + record.incidentId()
            );
            matrixPublisher.sendImage(
                    matrixCfg,
                    roomId,
                    png,
                    "SL400 FFT (" + label + ") id=" + record.incidentId(),
                    "image/png"
            );
            sendTextSafely(
                    matrixCfg,
                    roomId,
                    String.format(
                            java.util.Locale.US,
                            "SL400 FFT for incident %s: dominant=%.0f Hz, centroid=%.0f Hz",
                            record.incidentId(),
                            spectrum.dominantFrequencyHz(),
                            spectrum.spectralCentroidHz()
                    )
            );
        } finally {
            if (png != null) {
                try {
                    Files.deleteIfExists(png);
                } catch (Exception e) {
                    LOG.debug("Failed to delete temp FFT {}", png, e);
                }
            }
        }
    }

    private boolean hasLocalClip(IncidentRecord record) {
        if (record == null || record.clipPath() == null || record.clipPath().isBlank()) {
            return false;
        }
        try {
            return Files.exists(Path.of(record.clipPath()));
        } catch (Exception e) {
            return false;
        }
    }
}
