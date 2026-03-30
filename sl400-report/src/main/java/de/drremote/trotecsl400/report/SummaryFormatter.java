package de.drremote.trotecsl400.report;

import de.drremote.trotecsl400.api.IncidentRecord;

import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class SummaryFormatter {
    private SummaryFormatter() {
    }

    public static String buildSummaryMessage(
            List<IncidentRecord> incidents,
            String label,
            Boolean audioRunning
    ) {
        if (incidents == null || incidents.isEmpty()) {
            return "SL400 summary (" + label + "): no incidents.";
        }

        IncidentRecord maxMetricIncident = incidents.stream()
                .filter(i -> i.metricValue() != null)
                .max(Comparator.comparingDouble(IncidentRecord::metricValue))
                .orElse(null);

        Double maxLaeq5 = incidents.stream()
                .map(IncidentRecord::laEq5Min)
                .filter(v -> v != null)
                .max(Double::compareTo)
                .orElse(null);

        long timeAboveMs = incidents.stream()
                .mapToLong(IncidentRecord::timeAboveThresholdMs1Min)
                .sum();

        long clipsSaved = incidents.stream()
                .filter(i -> i.clipPath() != null && !i.clipPath().isBlank())
                .count();

        List<Double> metricValues = incidents.stream()
                .map(IncidentRecord::metricValue)
                .filter(v -> v != null)
                .toList();

        Double avgMetric = metricValues.isEmpty()
                ? null
                : metricValues.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);

        Long earliest = incidents.stream()
                .map(IncidentRecord::timestampMs)
                .min(Long::compareTo)
                .orElse(null);

        Long latest = incidents.stream()
                .map(IncidentRecord::timestampMs)
                .max(Long::compareTo)
                .orElse(null);

        String topHints = incidents.stream()
                .map(IncidentRecord::audioHint)
                .filter(h -> h != null && !h.isBlank())
                .collect(Collectors.groupingBy(h -> h, Collectors.counting()))
                .entrySet()
                .stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(3)
                .map(e -> e.getKey() + " (" + e.getValue() + ")")
                .collect(Collectors.joining(", "));

        SimpleDateFormat formatter = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

        List<IncidentRecord> topIncidents = incidents.stream()
                .sorted((a, b) -> {
                    double av = a.metricValue() == null ? Double.NEGATIVE_INFINITY : a.metricValue();
                    double bv = b.metricValue() == null ? Double.NEGATIVE_INFINITY : b.metricValue();
                    return Double.compare(bv, av);
                })
                .limit(3)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("SL400 Summary - ").append(label).append("\n\n");
        sb.append("Incidents: ").append(incidents.size()).append("\n");
        sb.append("Highest incident: ").append(IncidentFormatting.formatDb(
                maxMetricIncident == null ? null : maxMetricIncident.metricValue()
        )).append("\n");
        sb.append("Highest LAeq 5 min: ").append(IncidentFormatting.formatDb(maxLaeq5)).append("\n");
        if (avgMetric != null && !avgMetric.isNaN()) {
            sb.append("Average incident: ").append(IncidentFormatting.formatDb(avgMetric)).append("\n");
        }
        sb.append("Time above threshold: ").append(IncidentFormatting.formatDuration(timeAboveMs)).append("\n");
        sb.append("Saved clips: ").append(clipsSaved).append("\n");

        if (audioRunning == null) {
            sb.append("Audio buffer: unknown\n");
        } else if (audioRunning) {
            sb.append("Audio buffer: running\n");
        } else {
            sb.append("Audio buffer: stopped\n");
        }

        if (earliest != null && latest != null) {
            sb.append("First/last incident: ")
                    .append(formatter.format(new Date(earliest)))
                    .append(" / ")
                    .append(formatter.format(new Date(latest)))
                    .append("\n");
        }

        if (!topHints.isBlank()) {
            sb.append("Common audio hints: ").append(topHints).append("\n");
        }

        sb.append("\nTop incidents:\n");
        int idx = 1;
        for (IncidentRecord incident : topIncidents) {
            String time = formatter.format(new Date(incident.timestampMs()));
            String metricLabel = IncidentFormatting.formatMetricMode(incident.metricMode());
            String metricValue = IncidentFormatting.formatDb(incident.metricValue());
            String clip = incident.clipPath() != null && !incident.clipPath().isBlank() ? "yes" : "no";
            String hint = incident.audioHint();

            sb.append(idx++)
                    .append(") ")
                    .append(time)
                    .append(" | id=").append(incident.incidentId())
                    .append(" | ").append(metricLabel).append(" ").append(metricValue);

            if (hint != null && !hint.isBlank()) {
                sb.append(" | ").append(hint);
            }

            sb.append(" | clip=").append(clip).append("\n");
        }

        return sb.toString().trim();
    }
}
