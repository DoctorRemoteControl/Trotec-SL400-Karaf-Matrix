package de.drremote.trotecsl400.report;

import de.drremote.trotecsl400.api.MetricMode;

import java.util.Locale;

public final class IncidentFormatting {
    private IncidentFormatting() {
    }

    public static String formatDb(Double value) {
        return value == null ? "n/a" : String.format(Locale.US, "%.1f dB", value);
    }

    public static String formatDuration(long ms) {
        long totalSeconds = ms / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    public static String formatMetricMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "unknown";
        }
        return switch (mode) {
            case "LIVE" -> "Live";
            case "LAEQ_1_MIN" -> "LAeq 1 min";
            case "LAEQ_5_MIN" -> "LAeq 5 min";
            case "LAEQ_15_MIN" -> "LAeq 15 min";
            case "MAX_1_MIN" -> "Max 1 min";
            default -> mode;
        };
    }

    public static String formatMetricMode(MetricMode mode) {
        if (mode == null) {
            return "unknown";
        }
        return formatMetricMode(mode.name());
    }
}
