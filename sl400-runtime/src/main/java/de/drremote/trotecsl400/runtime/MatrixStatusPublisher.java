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
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(immediate = true)
public class MatrixStatusPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(MatrixStatusPublisher.class);
    private static final long STATUS_INTERVAL_MS = 15_000L;
    private static final long OFFLINE_THRESHOLD_MS = 15_000L;

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

    @Activate
    void activate() {
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
                MatrixConfig cfg = matrixConfigService.getConfig();
                if (!cfg.enabled() || cfg.roomId() == null || cfg.roomId().isBlank()
                        || cfg.accessToken() == null || cfg.accessToken().isBlank()) {
                    sleepQuiet(STATUS_INTERVAL_MS);
                    continue;
                }
                LiveSnapshot snapshot = liveStateService.snapshot();
                Sl400Sample sample = snapshot.lastSample();
                AcousticMetrics metrics = snapshot.lastMetrics();
                if (sample == null || metrics == null) {
                    sleepQuiet(STATUS_INTERVAL_MS);
                    continue;
                }
                boolean serialOnline = (System.currentTimeMillis() - snapshot.lastSampleAtMs())
                        <= OFFLINE_THRESHOLD_MS;
                boolean audioRunning = audioStatusService.getStatus().running();
                matrixPublisher.sendStatus(cfg, sample, metrics, serialOnline, audioRunning);
            } catch (Exception e) {
                LOG.debug("Matrix status publish failed: {}", e.getMessage());
                liveStateService.recordError("status publish failed: " + e.getMessage());
            }
            sleepQuiet(STATUS_INTERVAL_MS);
        }
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
