package de.drremote.trotecsl400.core;

import de.drremote.trotecsl400.api.AcousticMetrics;
import de.drremote.trotecsl400.api.Sl400Sample;

import java.util.ArrayDeque;
import java.util.List;

import static java.lang.Math.log10;
import static java.lang.Math.pow;

public class AcousticMetricsEngine {
    private final ArrayDeque<Sl400Sample> samples = new ArrayDeque<>();

    public void reset() {
        samples.clear();
    }

    public AcousticMetrics addSample(Sl400Sample sample, double thresholdDb) {
        samples.addLast(sample);
        long now = sample.timestampMs();
        trimOld(now);

        WindowStats stats1 = windowStats(now, ONE_MINUTE_MS, thresholdDb);
        WindowStats stats5 = windowStats(now, FIVE_MINUTES_MS, null);
        WindowStats stats15 = windowStats(now, FIFTEEN_MINUTES_MS, null);

        return new AcousticMetrics(
                now,
                sample.db(),
                laEqFromStats(stats1),
                laEqFromStats(stats5),
                laEqFromStats(stats15),
                maxForWindow(now, ONE_MINUTE_MS),
                stats1.timeAboveThresholdMs,
                stats1.totalDurationMs,
                stats5.totalDurationMs,
                stats15.totalDurationMs
        );
    }

    private void trimOld(long now) {
        while (!samples.isEmpty() && now - samples.peekFirst().timestampMs() > FIFTEEN_MINUTES_MS) {
            samples.removeFirst();
        }
    }

    private List<Sl400Sample> windowSamples(long now, long windowMs) {
        long windowStart = now - windowMs;
        Sl400Sample lastBefore = null;
        List<Sl400Sample> out = new java.util.ArrayList<>();
        for (Sl400Sample s : samples) {
            long ts = s.timestampMs();
            if (ts < windowStart) {
                lastBefore = s;
                continue;
            }
            if (ts > now) {
                break;
            }
            if (lastBefore != null) {
                out.add(lastBefore);
                lastBefore = null;
            }
            out.add(s);
        }
        if (out.isEmpty() && lastBefore != null) {
            out.add(lastBefore);
        }
        return out;
    }

    private Double laEqFromStats(WindowStats stats) {
        if (stats.totalDurationMs <= 0L) return null;
        double meanEnergy = stats.energyTimeSum / (double) stats.totalDurationMs;
        return 10.0 * log10(meanEnergy);
    }

    private Double maxForWindow(long now, long windowMs) {
        List<Sl400Sample> ws = windowSamples(now, windowMs);
        return ws.stream()
                .mapToDouble(Sl400Sample::db)
                .boxed()
                .max(Double::compareTo)
                .orElse(null);
    }

    private WindowStats windowStats(long now, long windowMs, Double thresholdDb) {
        long windowStart = now - windowMs;
        List<Sl400Sample> windowSamples = windowSamples(now, windowMs);
        if (windowSamples.isEmpty()) return new WindowStats(0L, 0.0, 0L);

        long totalDurationMs = 0L;
        double energyTimeSum = 0.0;
        long timeAboveThresholdMs = 0L;

        for (int i = 0; i < windowSamples.size(); i++) {
            Sl400Sample current = windowSamples.get(i);
            long start = Math.max(current.timestampMs(), windowStart);
            long end = (i + 1 < windowSamples.size())
                    ? Math.min(windowSamples.get(i + 1).timestampMs(), now)
                    : now;
            long rawDt = end - start;
            long dt = Math.max(0L, Math.min(rawDt, MAX_VALID_SEGMENT_MS));
            if (dt > 0L) {
                totalDurationMs += dt;
                double energy = pow(10.0, current.db() / 10.0);
                energyTimeSum += energy * (double) dt;
                if (thresholdDb != null && current.db() >= thresholdDb) {
                    timeAboveThresholdMs += dt;
                }
            }
        }

        return new WindowStats(totalDurationMs, energyTimeSum, timeAboveThresholdMs);
    }

    private static final class WindowStats {
        final long totalDurationMs;
        final double energyTimeSum;
        final long timeAboveThresholdMs;

        private WindowStats(long totalDurationMs, double energyTimeSum, long timeAboveThresholdMs) {
            this.totalDurationMs = totalDurationMs;
            this.energyTimeSum = energyTimeSum;
            this.timeAboveThresholdMs = timeAboveThresholdMs;
        }
    }

    private static final long ONE_MINUTE_MS = 60_000L;
    private static final long FIVE_MINUTES_MS = 5 * ONE_MINUTE_MS;
    private static final long FIFTEEN_MINUTES_MS = 15 * ONE_MINUTE_MS;
    private static final long MAX_VALID_SEGMENT_MS = 2_000L;
}
