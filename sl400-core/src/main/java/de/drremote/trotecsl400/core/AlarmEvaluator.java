package de.drremote.trotecsl400.core;

import de.drremote.trotecsl400.api.AcousticMetrics;
import de.drremote.trotecsl400.api.AlertConfig;
import de.drremote.trotecsl400.api.MetricMode;
import de.drremote.trotecsl400.api.SendMode;

public class AlarmEvaluator {
    private boolean isAbove = false;
    private long lastSentAt = 0L;

    public void reset() {
        isAbove = false;
        lastSentAt = 0L;
    }

    public boolean shouldSend(AcousticMetrics metrics, AlertConfig cfg) {
        if (!cfg.enabled()) {
            return false;
        }
        if (!hasRequiredCoverage(metrics, cfg.metricMode())) {
            return false;
        }

        long now = metrics.timestampMs();
        Double value = switch (cfg.metricMode()) {
            case LIVE -> metrics.currentDb();
            case LAEQ_1_MIN -> metrics.laEq1Min();
            case LAEQ_5_MIN -> metrics.laEq5Min();
            case LAEQ_15_MIN -> metrics.laEq15Min();
            case MAX_1_MIN -> metrics.maxDb1Min();
        };
        if (value == null) {
            return false;
        }

        boolean above = value >= cfg.thresholdDb();
        double resetLevel = cfg.thresholdDb() - cfg.hysteresisDb();

        if (!isAbove && above) {
            isAbove = true;
            lastSentAt = now;
            return true;
        }

        if (isAbove && value <= resetLevel) {
            isAbove = false;
        }

        // Repeat alarms only while the metric is actually still above the threshold.
        // Do not use the hysteresis state alone for periodic sending, otherwise alarms
        // may continue even after the value has already dropped below thresholdDb.
        if (cfg.sendMode() == SendMode.PERIODIC_WHILE_ABOVE
                && above
                && now - lastSentAt >= cfg.minSendIntervalMs()) {
            lastSentAt = now;
            return true;
        }

        return false;
    }

    private boolean hasRequiredCoverage(AcousticMetrics metrics, MetricMode mode) {
        return switch (mode) {
            case LAEQ_1_MIN -> metrics.coverage1MinMs() >= ONE_MINUTE_MS;
            case LAEQ_5_MIN -> metrics.coverage5MinMs() >= FIVE_MINUTES_MS;
            case LAEQ_15_MIN -> metrics.coverage15MinMs() >= FIFTEEN_MINUTES_MS;
            default -> true;
        };
    }

    private static final long ONE_MINUTE_MS = 60_000L;
    private static final long FIVE_MINUTES_MS = 5 * ONE_MINUTE_MS;
    private static final long FIFTEEN_MINUTES_MS = 15 * ONE_MINUTE_MS;
}
