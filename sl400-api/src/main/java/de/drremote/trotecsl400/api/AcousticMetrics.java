package de.drremote.trotecsl400.api;

public record AcousticMetrics(
        long timestampMs,
        double currentDb,
        Double laEq1Min,
        Double laEq5Min,
        Double laEq15Min,
        Double maxDb1Min,
        long timeAboveThresholdMs1Min,
        long coverage1MinMs,
        long coverage5MinMs,
        long coverage15MinMs
) {}
