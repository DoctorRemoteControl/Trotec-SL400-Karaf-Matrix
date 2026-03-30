package de.drremote.trotecsl400.api;

public record LiveSnapshot(
        Sl400Sample lastSample,
        AcousticMetrics lastMetrics,
        long lastSampleAtMs,
        String lastError
) {
    public static LiveSnapshot empty() {
        return new LiveSnapshot(null, null, 0L, null);
    }
}
