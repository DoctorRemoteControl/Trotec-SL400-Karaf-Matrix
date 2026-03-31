package de.drremote.trotecsl400.runtime;

import de.drremote.trotecsl400.api.AcousticMetrics;
import de.drremote.trotecsl400.api.AudioStatusService;
import de.drremote.trotecsl400.api.LiveSnapshot;
import de.drremote.trotecsl400.api.LiveStateService;
import de.drremote.trotecsl400.api.MatrixConfig;
import de.drremote.trotecsl400.api.MatrixConfigService;
import de.drremote.trotecsl400.api.MatrixPublisher;
import de.drremote.trotecsl400.api.Sl400Sample;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(immediate = true, configurationPid = "de.drremote.trotecsl400.matrix.status")
@Designate(ocd = MatrixStatusPublisher.Config.class)
public class MatrixStatusPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(MatrixStatusPublisher.class);

    @ObjectClassDefinition(name = "Trotec SL400 Matrix Status Publishing")
    public @interface Config {
        @AttributeDefinition
        boolean enabled() default true;

        @AttributeDefinition
        long publishIntervalMs() default 60000;

        @AttributeDefinition
        long maxSilenceMs() default 300000;

        @AttributeDefinition
        long offlineThresholdMs() default 15000;

        @AttributeDefinition
        boolean onlyOnChange() default true;

        @AttributeDefinition
        double statusDbStep() default 1.0;

        @AttributeDefinition
        String statusRoomId() default "";
    }

    @Reference
    private MatrixConfigService matrixConfigService;

    @Reference
    private MatrixPublisher matrixPublisher;

    @Reference
    private LiveStateService liveStateService;

    @Reference
    private AudioStatusService audioStatusService;

    private volatile boolean running;
    private volatile Thread worker;
    private volatile Config config;
    private volatile String lastFingerprint;
    private volatile long lastPublishAtMs;

    @Activate
    @Modified
    void activate(Config cfg) {
        this.config = cfg;
        start();
    }

    @Deactivate
    void deactivate() {
        stop();
    }

    private void start() {
        if (running) return;
        running = true;
        worker = new Thread(this::runLoop, "sl400-matrix-status");
        worker.setDaemon(true);
        worker.start();
    }

    private void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void runLoop() {
        while (running) {
            try {
                Config statusCfg = this.config;
                if (statusCfg == null || !statusCfg.enabled()) {
                    sleepQuiet(1000);
                    continue;
                }
                MatrixConfig matrixCfg = matrixConfigService.getConfig();
                if (!matrixCfg.enabled() || matrixCfg.roomId() == null || matrixCfg.roomId().isBlank()
                        || matrixCfg.accessToken() == null || matrixCfg.accessToken().isBlank()) {
                    sleepQuiet(statusCfg.publishIntervalMs());
                    continue;
                }
                LiveSnapshot snapshot = liveStateService.snapshot();
                Sl400Sample sample = snapshot.lastSample();
                AcousticMetrics metrics = snapshot.lastMetrics();
                if (sample == null || metrics == null) {
                    sleepQuiet(statusCfg.publishIntervalMs());
                    continue;
                }
                boolean serialOnline = (System.currentTimeMillis() - snapshot.lastSampleAtMs())
                        <= statusCfg.offlineThresholdMs();
                boolean audioRunning = audioStatusService.getStatus().running();
                String fingerprint = buildFingerprint(sample, metrics, serialOnline, audioRunning, statusCfg.statusDbStep());
                long now = System.currentTimeMillis();
                boolean changed = lastFingerprint == null || !lastFingerprint.equals(fingerprint);
                boolean shouldSend = !statusCfg.onlyOnChange()
                        || changed
                        || (statusCfg.maxSilenceMs() > 0 && (now - lastPublishAtMs) >= statusCfg.maxSilenceMs());
                if (shouldSend) {
                    MatrixConfig target = applyStatusRoomOverride(matrixCfg, statusCfg.statusRoomId());
                    matrixPublisher.sendStatus(target, sample, metrics, serialOnline, audioRunning);
                    lastFingerprint = fingerprint;
                    lastPublishAtMs = now;
                }
            } catch (Exception e) {
                LOG.debug("Matrix status publish failed: {}", e.getMessage());
                liveStateService.recordError("status publish failed: " + e.getMessage());
            }
            Config statusCfg = this.config;
            sleepQuiet(statusCfg == null ? 1000 : statusCfg.publishIntervalMs());
        }
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private MatrixConfig applyStatusRoomOverride(MatrixConfig base, String statusRoomId) {
        if (statusRoomId == null || statusRoomId.isBlank()) {
            return base;
        }
        return new MatrixConfig(
                base.homeserverBaseUrl(),
                base.accessToken(),
                statusRoomId.trim(),
                base.deviceId(),
                base.enabled()
        );
    }

    private String buildFingerprint(Sl400Sample sample, AcousticMetrics metrics,
                                    boolean serialOnline, boolean audioRunning, double dbStep) {
        double current = bucket(sample.db(), dbStep);
        Double max1 = bucketNullable(metrics.maxDb1Min(), dbStep);
        Double l1 = bucketNullable(metrics.laEq1Min(), dbStep);
        Double l5 = bucketNullable(metrics.laEq5Min(), dbStep);
        Double l15 = bucketNullable(metrics.laEq15Min(), dbStep);
        return serialOnline + "|" + audioRunning + "|" + current
                + "|" + max1 + "|" + l1 + "|" + l5 + "|" + l15;
    }

    private double bucket(double value, double step) {
        if (step <= 0.0) {
            return value;
        }
        return Math.round(value / step) * step;
    }

    private Double bucketNullable(Double value, double step) {
        if (value == null) {
            return null;
        }
        return bucket(value, step);
    }
}
