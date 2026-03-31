package de.drremote.trotecsl400.api;

public record IncidentRecord(
        String incidentId,
        long timestampMs,
        String roomId,
        String metricMode,
        Double metricValue,
        double thresholdDb,
        Double hysteresisDb,
        Double laEq1Min,
        Double laEq5Min,
        Double laEq15Min,
        Double maxDb1Min,
        long timeAboveThresholdMs1Min,
        String clipPath,
        boolean clipUploaded,
        String mxcUrl,
        String audioHint
) {}
