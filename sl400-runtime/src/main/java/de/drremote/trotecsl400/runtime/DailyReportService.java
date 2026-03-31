package de.drremote.trotecsl400.runtime;

import de.drremote.trotecsl400.api.AlertConfig;
import de.drremote.trotecsl400.api.AlertConfigService;
import de.drremote.trotecsl400.api.IncidentRecord;
import de.drremote.trotecsl400.api.IncidentRepository;
import de.drremote.trotecsl400.api.MatrixConfig;
import de.drremote.trotecsl400.api.MatrixConfigService;
import de.drremote.trotecsl400.api.MatrixPublisher;
import de.drremote.trotecsl400.report.JsonExporter;
import de.drremote.trotecsl400.report.IncidentGraphRenderer;
import de.drremote.trotecsl400.report.SummaryFormatter;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component(immediate = true)
public class DailyReportService {
    private static final Logger LOG = LoggerFactory.getLogger(DailyReportService.class);

    @Reference
    private AlertConfigService alertConfigService;

    @Reference
    private MatrixConfigService matrixConfigService;

    @Reference
    private MatrixPublisher matrixPublisher;

    @Reference
    private IncidentRepository incidentRepository;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sl400-daily-report");
                t.setDaemon(true);
                return t;
            });

    private volatile LocalDate lastReportedDate;
    private volatile Path lastReportedFile;

    @Activate
    void activate() {
        initLastReportedFile();
        scheduler.scheduleWithFixedDelay(this::tick, 5, 60, TimeUnit.SECONDS);
    }

    @Deactivate
    void deactivate() {
        scheduler.shutdownNow();
    }

    private void tick() {
        try {
            AlertConfig alertCfg = alertConfigService.getConfig();
            if (!alertCfg.dailyReportEnabled()) {
                return;
            }

            LocalDate nowDate = LocalDate.now();
            if (lastReportedDate != null && lastReportedDate.equals(nowDate.minusDays(1))) {
                return;
            }

            LocalDateTime now = LocalDateTime.now();
            if (now.getHour() != alertCfg.dailyReportHour()
                    || now.getMinute() != alertCfg.dailyReportMinute()) {
                return;
            }

            MatrixConfig matrixCfg = matrixConfigService.getConfig();
            if (!matrixCfg.enabled()) {
                return;
            }

            String roomId = alertCfg.dailyReportRoomId();
            if (roomId == null || roomId.isBlank()) {
                roomId = alertCfg.targetRoomId();
            }
            if (roomId == null || roomId.isBlank()) {
                LOG.debug("Daily report skipped: no room configured.");
                return;
            }

            LocalDate reportDay = nowDate.minusDays(1);
            long[] range = dayRange(reportDay);
            List<IncidentRecord> incidents =
                    incidentRepository.getIncidentsBetween(range[0], range[1]);

            sendReport(matrixCfg, roomId, incidents, alertCfg, "yesterday");
            lastReportedDate = reportDay;
            persistLastReportedDate(reportDay);
        } catch (Exception e) {
            LOG.warn("Daily report tick failed: {}", e.getMessage());
        }
    }

    private void initLastReportedFile() {
        try {
            String base = System.getProperty("karaf.data", "data");
            Path baseDir = Path.of(base, "sl400");
            Files.createDirectories(baseDir);
            lastReportedFile = baseDir.resolve("daily-report-last.txt");
            if (Files.exists(lastReportedFile)) {
                String text = Files.readString(lastReportedFile).trim();
                if (!text.isBlank()) {
                    lastReportedDate = LocalDate.parse(text);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to load daily report state: {}", e.getMessage());
        }
    }

    private void persistLastReportedDate(LocalDate date) {
        if (lastReportedFile == null || date == null) {
            return;
        }
        try {
            Files.writeString(
                    lastReportedFile,
                    date.toString(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE
            );
        } catch (Exception e) {
            LOG.debug("Failed to persist daily report state: {}", e.getMessage());
        }
    }

    private long[] dayRange(LocalDate date) {
        ZoneId zone = ZoneId.systemDefault();
        long start = date.atStartOfDay(zone).toInstant().toEpochMilli();
        long end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        return new long[]{start, end};
    }

    private void sendReport(MatrixConfig matrixCfg, String roomId,
                            List<IncidentRecord> incidents, AlertConfig alertCfg,
                            String label) {
        try {
            String summary = SummaryFormatter.buildSummaryMessage(incidents, label, null);
            matrixPublisher.sendText(matrixCfg, roomId, summary);
        } catch (Exception e) {
            LOG.warn("Daily report summary failed: {}", e.getMessage());
        }

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
            LOG.warn("Daily report JSON failed: {}", e.getMessage());
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
            LOG.warn("Daily report graph failed: {}", e.getMessage());
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
}
