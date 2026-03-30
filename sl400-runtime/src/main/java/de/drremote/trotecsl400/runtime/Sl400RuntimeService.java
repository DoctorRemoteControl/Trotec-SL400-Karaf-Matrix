package de.drremote.trotecsl400.runtime;

import de.drremote.trotecsl400.api.AcousticMetrics;
import de.drremote.trotecsl400.api.AlertConfig;
import de.drremote.trotecsl400.api.AlertConfigService;
import de.drremote.trotecsl400.api.IncidentRecord;
import de.drremote.trotecsl400.api.IncidentRepository;
import de.drremote.trotecsl400.api.MatrixConfig;
import de.drremote.trotecsl400.api.MatrixConfigService;
import de.drremote.trotecsl400.api.MatrixPublisher;
import de.drremote.trotecsl400.api.Sl400Sample;
import de.drremote.trotecsl400.api.Sl400Source;
import de.drremote.trotecsl400.api.LiveStateService;
import de.drremote.trotecsl400.core.AcousticMetricsEngine;
import de.drremote.trotecsl400.core.AlarmEvaluator;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component(immediate = true)
public class Sl400RuntimeService {
    private static final Logger LOG = LoggerFactory.getLogger(Sl400RuntimeService.class);
    private static final long SENT_TTL_MS = 6 * 60 * 60 * 1000L;
    private static final long HINT_WAIT_MS = 5000L;
    @Reference
    private Sl400Source sl400Source;

    @Reference
    private MatrixPublisher matrixPublisher;

    @Reference
    private IncidentRepository incidentRepository;

    @Reference
    private AlertConfigService alertConfigService;

    @Reference
    private MatrixConfigService matrixConfigService;

    @Reference
    private AudioCaptureService audioCaptureService;

    @Reference
    private AudioHintService audioHintService;

    @Reference
    private LiveStateService liveStateService;

    private final AlarmEvaluator alarmEvaluator = new AlarmEvaluator();
    private final AcousticMetricsEngine metricsEngine = new AcousticMetricsEngine();
    private final ExecutorService alertExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sl400-alert-dispatch");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, Long> sentAlerts = new ConcurrentHashMap<>();
    private final java.util.Set<String> inFlight = ConcurrentHashMap.newKeySet();

    @Activate
    void activate() {
        sl400Source.setListener(this::onSample);
        sl400Source.start();
    }

    @Deactivate
    void deactivate() {
        sl400Source.stop();
        alertExecutor.shutdownNow();
    }

    private void onSample(Sl400Sample sample) {
        AlertConfig alertCfg = alertConfigService.getConfig();
        MatrixConfig matrixCfg = matrixConfigService.getConfig();
        AcousticMetrics metrics = metricsEngine.addSample(sample, alertCfg.thresholdDb());
        liveStateService.updateSample(sample, metrics);

        if (matrixCfg.enabled() && alarmEvaluator.shouldSend(metrics, alertCfg)) {
            String targetRoomId = !alertCfg.targetRoomId().isBlank()
                    ? alertCfg.targetRoomId()
                    : matrixCfg.roomId();
            MatrixConfig targetCfg = new MatrixConfig(
                    matrixCfg.homeserverBaseUrl(),
                    matrixCfg.accessToken(),
                    targetRoomId,
                    matrixCfg.deviceId(),
                    matrixCfg.enabled()
            );

            String alertId = String.valueOf(sample.timestampMs());
            if (isAlreadySent(alertId)) {
                return;
            }
            if (!inFlight.add(alertId)) {
                return;
            }
            IncidentRecord incident = new IncidentRecord(
                    alertId,
                    sample.timestampMs(),
                    targetRoomId,
                    alertCfg.metricMode().name(),
                    metricValue(metrics, alertCfg),
                    alertCfg.thresholdDb(),
                    metrics.laEq1Min(),
                    metrics.laEq5Min(),
                    metrics.laEq15Min(),
                    metrics.maxDb1Min(),
                    metrics.timeAboveThresholdMs1Min(),
                    null,
                    false,
                    null,
                    null
            );

            try {
                incidentRepository.add(incident);
                dispatchAlertAsync(alertId, sample, metrics, alertCfg, targetCfg);
            } catch (Exception e) {
                LOG.error("Failed to persist/send alert for sample {}", sample.timestampMs(), e);
                liveStateService.recordError("incident persist/send failed: " + e.getMessage());
                inFlight.remove(alertId);
            }
        }
    }

    private void dispatchAlertAsync(String alertId,
                                    Sl400Sample sample,
                                    AcousticMetrics metrics,
                                    AlertConfig alertCfg,
                                    MatrixConfig targetCfg) {
        alertExecutor.execute(() -> {
            try {
                CompletableFuture<String> hintFuture = captureAndAnalyzeAsync(alertId);
                boolean waitForHint = alertCfg.alertHintFollowupEnabled() && audioCaptureService.isRunning();

                AtomicBoolean sent = new AtomicBoolean(false);
                AtomicBoolean sentWithHint = new AtomicBoolean(false);

                CompletableFuture<String> timedHint = waitForHint
                        ? hintFuture.completeOnTimeout(null, HINT_WAIT_MS, TimeUnit.MILLISECONDS)
                        : CompletableFuture.completedFuture(null);

                timedHint.whenComplete((hint, err) -> {
                    try {
                        matrixPublisher.sendAlert(targetCfg, sample, metrics, alertCfg, hint);
                        sent.set(true);
                        sentWithHint.set(hint != null && !hint.isBlank());
                        markSent(alertId);
                    } catch (Exception e) {
                        LOG.warn("Failed to send alert {}: {}", alertId, e.getMessage());
                    } finally {
                        inFlight.remove(alertId);
                    }
                });

                hintFuture.thenAccept(hint -> {
                    if (hint == null || hint.isBlank()) return;
                    if (!alertCfg.alertHintFollowupEnabled()) return;
                    if (sent.get() && !sentWithHint.get()) {
                        try {
                            matrixPublisher.sendText(
                                    targetCfg,
                                    targetCfg.roomId(),
                                    "SL400 audio hint for incident " + alertId + ": " + hint
                            );
                        } catch (Exception e) {
                            LOG.debug("Failed to send alert follow-up for {}: {}", alertId, e.getMessage());
                        }
                    }
                });
            } catch (Exception e) {
                LOG.warn("Alert dispatch failed: {}", e.getMessage());
                inFlight.remove(alertId);
            }
        });
    }

    private CompletableFuture<String> captureAndAnalyzeAsync(String alertId) {
        if (!audioCaptureService.isRunning()) {
            return CompletableFuture.completedFuture(null);
        }
        return audioCaptureService.captureClipAsync(alertId)
                .thenCompose(path -> {
                    if (path == null) {
                        return CompletableFuture.completedFuture(null);
                    }
                    try {
                        incidentRepository.updateClip(alertId, path.toString());
                    } catch (Exception e) {
                        LOG.warn("Failed to update clip for incident {}", alertId, e);
                    }
                    return audioHintService.analyzeAsync(
                            alertId,
                            path,
                            audioCaptureService.getSampleRate()
                    );
                });
    }

    private boolean isAlreadySent(String alertId) {
        Long ts = sentAlerts.get(alertId);
        if (ts == null) return false;
        if (System.currentTimeMillis() - ts > SENT_TTL_MS) {
            sentAlerts.remove(alertId);
            return false;
        }
        return true;
    }

    private void markSent(String alertId) {
        sentAlerts.put(alertId, System.currentTimeMillis());
    }

    private Double metricValue(AcousticMetrics metrics, AlertConfig cfg) {
        return switch (cfg.metricMode()) {
            case LIVE -> metrics.currentDb();
            case LAEQ_1_MIN -> metrics.laEq1Min();
            case LAEQ_5_MIN -> metrics.laEq5Min();
            case LAEQ_15_MIN -> metrics.laEq15Min();
            case MAX_1_MIN -> metrics.maxDb1Min();
        };
    }
}
