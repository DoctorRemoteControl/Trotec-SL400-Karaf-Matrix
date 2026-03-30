package de.drremote.trotecsl400.api;

import java.util.List;

public record AlertConfig(
        boolean enabled,
        double thresholdDb,
        double hysteresisDb,
        long minSendIntervalMs,
        SendMode sendMode,
        MetricMode metricMode,
        List<String> allowedSenders,
        String commandRoomId,
        String targetRoomId,
        boolean alertHintFollowupEnabled,
        boolean dailyReportEnabled,
        int dailyReportHour,
        int dailyReportMinute,
        String dailyReportRoomId,
        boolean dailyReportJsonEnabled,
        boolean dailyReportGraphEnabled
) {
    public static AlertConfig defaults() {
        return new AlertConfig(
                false,
                70.0,
                2.0,
                60_000L,
                SendMode.CROSSING_ONLY,
                MetricMode.LAEQ_5_MIN,
                List.of(),
                "",
                "",
                true,
                false,
                9,
                0,
                "",
                true,
                true
        );
    }
}
