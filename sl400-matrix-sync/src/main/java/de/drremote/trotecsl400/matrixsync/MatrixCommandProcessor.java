package de.drremote.trotecsl400.matrixsync;

import de.drremote.trotecsl400.api.AlertConfig;
import de.drremote.trotecsl400.api.MetricMode;
import de.drremote.trotecsl400.api.SendMode;

import java.util.List;

public class MatrixCommandProcessor {
    private final List<MatrixCommandHandler> handlers = List.of(
            new MatrixHelpCommandHandler(),
            new MatrixConfigCommandHandler(),
            new MatrixQueryCommandHandler(),
            new MatrixExportCommandHandler(),
            new MatrixReportCommandHandler(),
            new MatrixGraphCommandHandler(),
            new MatrixClipCommandHandler(),
            new MatrixAudioCommandHandler()
    );

    public CommandResult process(String message, String senderUserId, String roomId, AlertConfig config) {
        if (message == null || message.isBlank()) return null;
        if (senderUserId == null || roomId == null) return null;
        if (config.commandRoomId() != null && !config.commandRoomId().isBlank()
                && !roomId.equals(config.commandRoomId())) {
            return null;
        }
        if (config.allowedSenders() != null && !config.allowedSenders().isEmpty()
                && !config.allowedSenders().contains(senderUserId)) {
            return null;
        }

        String trimmed = message.trim();
        if (!trimmed.startsWith("!sl400")) return null;
        List<String> parts = List.of(trimmed.split("\\s+"));
        if (parts.size() < 2) {
            return new CommandResult(config, "SL400: Unknown command. Try `!sl400 status`.");
        }
        for (MatrixCommandHandler handler : handlers) {
            CommandResult result = handler.handle(parts, config);
            if (result != null) return result;
        }
        return new CommandResult(config, "SL400: Unknown command `" + parts.get(1) + "`.");
    }

    public interface MatrixCommandHandler {
        CommandResult handle(List<String> parts, AlertConfig config);
    }

    public static class CommandResult {
        public final AlertConfig updatedConfig;
        public final String responseMessage;
        public final CommandAction action;

        public CommandResult(AlertConfig updatedConfig, String responseMessage) {
            this(updatedConfig, responseMessage, null);
        }

        public CommandResult(AlertConfig updatedConfig, String responseMessage, CommandAction action) {
            this.updatedConfig = updatedConfig;
            this.responseMessage = responseMessage;
            this.action = action;
        }
    }

    public sealed interface CommandAction permits
            IncidentsSince, IncidentsBetween, IncidentsToday, IncidentsYesterday,
            SummarySince, SummaryToday, SummaryYesterday,
            JsonSince, JsonToday, JsonYesterday,
            ReportNow, ReportToday, ReportYesterday, ReportSince,
            GraphSince, GraphToday, GraphYesterday,
            ClipLast, ClipIncident, ClipsSince,
            AudioStart, AudioStop, AudioStatus {
    }

    public record IncidentsSince(long durationMs, String label) implements CommandAction {}

    public record IncidentsBetween(long startMs, long endMs, String label) implements CommandAction {}

    public record IncidentsToday() implements CommandAction {}

    public record IncidentsYesterday() implements CommandAction {}

    public record SummarySince(long durationMs, String label) implements CommandAction {}

    public record SummaryToday() implements CommandAction {}

    public record SummaryYesterday() implements CommandAction {}

    public record JsonSince(long durationMs, String label) implements CommandAction {}

    public record JsonToday() implements CommandAction {}

    public record JsonYesterday() implements CommandAction {}

    public record ReportNow() implements CommandAction {}

    public record ReportToday() implements CommandAction {}

    public record ReportYesterday() implements CommandAction {}

    public record ReportSince(long durationMs, String label) implements CommandAction {}

    public record GraphSince(long durationMs, String label) implements CommandAction {}

    public record GraphToday() implements CommandAction {}

    public record GraphYesterday() implements CommandAction {}

    public record ClipLast() implements CommandAction {}

    public record ClipIncident(String incidentId) implements CommandAction {}

    public record ClipsSince(long durationMs, String label) implements CommandAction {}

    public record AudioStart() implements CommandAction {}

    public record AudioStop() implements CommandAction {}

    public record AudioStatus() implements CommandAction {}

    private static class MatrixHelpCommandHandler implements MatrixCommandHandler {
        @Override
        public CommandResult handle(List<String> parts, AlertConfig config) {
            if (parts.size() < 2) return null;
            if (!"help".equalsIgnoreCase(parts.get(1))) return null;
            String msg = "SL400 commands:\n" +
                    "!sl400 help\n" +
                    "!sl400 status | config\n" +
                    "!sl400 set threshold <db>\n" +
                    "!sl400 set hysteresis <db>\n" +
                    "!sl400 set metric live|laeq1|laeq5|laeq15|max1\n" +
                    "!sl400 set mode crossing|periodic\n" +
                    "!sl400 set interval <ms>\n" +
                    "!sl400 set dailyreport true|false\n" +
                    "!sl400 set reporttime HH:MM\n" +
                    "!sl400 set reportroom <roomId>\n" +
                    "!sl400 set reportjson true|false\n" +
                    "!sl400 set reportgraph true|false\n" +
                    "!sl400 set alerthint true|false\n" +
                    "!sl400 incidents today|yesterday|since <duration>|between <start> <end>\n" +
                    "!sl400 summary today|yesterday|since <duration>\n" +
                    "!sl400 json today|yesterday|since <duration>\n" +
                    "!sl400 graph today|yesterday|since <duration>\n" +
                    "!sl400 report now|today|yesterday|since <duration>\n" +
                    "!sl400 clip last\n" +
                    "!sl400 clip incident <id>\n" +
                    "!sl400 clips since <duration>\n" +
                    "!sl400 audio status|start|stop\n";
            return new CommandResult(config, msg);
        }
    }

    private static class MatrixConfigCommandHandler implements MatrixCommandHandler {
        @Override
        public CommandResult handle(List<String> parts, AlertConfig config) {
            if (parts.size() < 2) return null;
            String command = parts.get(1).toLowerCase();
            if ("status".equals(command) || "config".equals(command)) {
                return new CommandResult(config, statusMessage(config));
            }
            if ("set".equals(command)) {
                String key = parts.size() > 2 ? parts.get(2).toLowerCase() : null;
                String value = parts.size() > 3 ? parts.get(3) : null;
                if (key == null || key.isBlank()) {
                    return new CommandResult(config, "SL400: Usage `!sl400 set <key> <value>`.");
                }
                return handleSetting(key, value, config, true);
            }
            return handleSetting(command, parts.size() > 2 ? parts.get(2) : null, config, false);
        }

        private String statusMessage(AlertConfig cfg) {
            return "SL400: enabled=" + cfg.enabled() +
                    ", thresholdDb=" + cfg.thresholdDb() +
                    ", hysteresisDb=" + cfg.hysteresisDb() +
                    ", minSendIntervalMs=" + cfg.minSendIntervalMs() +
                    ", sendMode=" + cfg.sendMode() +
                    ", metricMode=" + cfg.metricMode() +
                    ", targetRoomId=" + cfg.targetRoomId() +
                    ", alertHintFollowupEnabled=" + cfg.alertHintFollowupEnabled() +
                    ", dailyReportEnabled=" + cfg.dailyReportEnabled() +
                    ", dailyReportTime=" + formatTime(cfg) +
                    ", dailyReportRoomId=" + cfg.dailyReportRoomId() +
                    ", dailyReportJsonEnabled=" + cfg.dailyReportJsonEnabled() +
                    ", dailyReportGraphEnabled=" + cfg.dailyReportGraphEnabled();
        }

        private CommandResult handleSetting(String command, String valueRaw,
                                            AlertConfig config, boolean fromSet) {
            switch (command) {
                case "enable": {
                    Boolean enabled = parseBoolean(valueRaw);
                    if (enabled == null) return usageMessage(config, fromSet, "enable", "true|false");
                    AlertConfig updated = config;
                    updated = new AlertConfig(
                            enabled,
                            config.thresholdDb(),
                            config.hysteresisDb(),
                            config.minSendIntervalMs(),
                            config.sendMode(),
                            config.metricMode(),
                            config.allowedSenders(),
                            config.commandRoomId(),
                            config.targetRoomId(),
                            config.alertHintFollowupEnabled(),
                            config.dailyReportEnabled(),
                            config.dailyReportHour(),
                            config.dailyReportMinute(),
                            config.dailyReportRoomId(),
                            config.dailyReportJsonEnabled(),
                            config.dailyReportGraphEnabled()
                    );
                    return new CommandResult(updated, "SL400: enabled=" + enabled);
                }
                case "threshold": {
                    Double value = parseDouble(valueRaw);
                    if (value == null) return usageMessage(config, fromSet, "threshold", "<number>");
                    AlertConfig updated = new AlertConfig(
                            config.enabled(), value, config.hysteresisDb(), config.minSendIntervalMs(),
                            config.sendMode(), config.metricMode(), config.allowedSenders(),
                            config.commandRoomId(), config.targetRoomId(),
                            config.alertHintFollowupEnabled(), config.dailyReportEnabled(),
                            config.dailyReportHour(), config.dailyReportMinute(),
                            config.dailyReportRoomId(), config.dailyReportJsonEnabled(),
                            config.dailyReportGraphEnabled()
                    );
                    return new CommandResult(updated, "SL400: thresholdDb=" + value);
                }
                case "hysteresis": {
                    Double value = parseDouble(valueRaw);
                    if (value == null) return usageMessage(config, fromSet, "hysteresis", "<number>");
                    AlertConfig updated = new AlertConfig(
                            config.enabled(), config.thresholdDb(), value, config.minSendIntervalMs(),
                            config.sendMode(), config.metricMode(), config.allowedSenders(),
                            config.commandRoomId(), config.targetRoomId(),
                            config.alertHintFollowupEnabled(), config.dailyReportEnabled(),
                            config.dailyReportHour(), config.dailyReportMinute(),
                            config.dailyReportRoomId(), config.dailyReportJsonEnabled(),
                            config.dailyReportGraphEnabled()
                    );
                    return new CommandResult(updated, "SL400: hysteresisDb=" + value);
                }
                case "interval": {
                    Long value = parseLong(valueRaw);
                    if (value == null || value < 0) return usageMessage(config, fromSet, "interval", "<millis>");
                    AlertConfig updated = new AlertConfig(
                            config.enabled(), config.thresholdDb(), config.hysteresisDb(), value,
                            config.sendMode(), config.metricMode(), config.allowedSenders(),
                            config.commandRoomId(), config.targetRoomId(),
                            config.alertHintFollowupEnabled(), config.dailyReportEnabled(),
                            config.dailyReportHour(), config.dailyReportMinute(),
                            config.dailyReportRoomId(), config.dailyReportJsonEnabled(),
                            config.dailyReportGraphEnabled()
                    );
                    return new CommandResult(updated, "SL400: minSendIntervalMs=" + value);
                }
                case "dailyreport": {
                    Boolean enabled = parseBoolean(valueRaw);
                    if (enabled == null) return usageMessage(config, fromSet, "dailyreport", "true|false");
                    AlertConfig updated = new AlertConfig(
                            config.enabled(), config.thresholdDb(), config.hysteresisDb(),
                            config.minSendIntervalMs(), config.sendMode(), config.metricMode(),
                            config.allowedSenders(), config.commandRoomId(), config.targetRoomId(),
                            config.alertHintFollowupEnabled(), enabled, config.dailyReportHour(),
                            config.dailyReportMinute(), config.dailyReportRoomId(),
                            config.dailyReportJsonEnabled(), config.dailyReportGraphEnabled()
                    );
                    return new CommandResult(updated, "SL400: dailyReportEnabled=" + enabled);
                }
                case "reporttime": {
                    int[] time = parseHourMinute(valueRaw);
                    if (time == null) return usageMessage(config, fromSet, "reporttime", "HH:MM");
                    AlertConfig updated = new AlertConfig(
                            config.enabled(), config.thresholdDb(), config.hysteresisDb(),
                            config.minSendIntervalMs(), config.sendMode(), config.metricMode(),
                            config.allowedSenders(), config.commandRoomId(), config.targetRoomId(),
                            config.alertHintFollowupEnabled(), config.dailyReportEnabled(),
                            time[0], time[1], config.dailyReportRoomId(),
                            config.dailyReportJsonEnabled(), config.dailyReportGraphEnabled()
                    );
                    return new CommandResult(updated, "SL400: dailyReportTime=" + formatTime(updated));
                }
                case "reportroom": {
                    if (valueRaw == null || valueRaw.isBlank()) {
                        return usageMessage(config, fromSet, "reportroom", "<roomId>");
                    }
                    AlertConfig updated = new AlertConfig(
                            config.enabled(), config.thresholdDb(), config.hysteresisDb(),
                            config.minSendIntervalMs(), config.sendMode(), config.metricMode(),
                            config.allowedSenders(), config.commandRoomId(), config.targetRoomId(),
                            config.alertHintFollowupEnabled(), config.dailyReportEnabled(),
                            config.dailyReportHour(), config.dailyReportMinute(), valueRaw,
                            config.dailyReportJsonEnabled(), config.dailyReportGraphEnabled()
                    );
                    return new CommandResult(updated, "SL400: dailyReportRoomId=" + valueRaw);
                }
                case "reportjson": {
                    Boolean enabled = parseBoolean(valueRaw);
                    if (enabled == null) return usageMessage(config, fromSet, "reportjson", "true|false");
                    AlertConfig updated = new AlertConfig(
                            config.enabled(), config.thresholdDb(), config.hysteresisDb(),
                            config.minSendIntervalMs(), config.sendMode(), config.metricMode(),
                            config.allowedSenders(), config.commandRoomId(), config.targetRoomId(),
                            config.alertHintFollowupEnabled(), config.dailyReportEnabled(),
                            config.dailyReportHour(), config.dailyReportMinute(), config.dailyReportRoomId(),
                            enabled, config.dailyReportGraphEnabled()
                    );
                    return new CommandResult(updated, "SL400: dailyReportJsonEnabled=" + enabled);
                }
                case "reportgraph": {
                    Boolean enabled = parseBoolean(valueRaw);
                    if (enabled == null) return usageMessage(config, fromSet, "reportgraph", "true|false");
                    AlertConfig updated = new AlertConfig(
                            config.enabled(), config.thresholdDb(), config.hysteresisDb(),
                            config.minSendIntervalMs(), config.sendMode(), config.metricMode(),
                            config.allowedSenders(), config.commandRoomId(), config.targetRoomId(),
                            config.alertHintFollowupEnabled(), config.dailyReportEnabled(),
                            config.dailyReportHour(), config.dailyReportMinute(), config.dailyReportRoomId(),
                            config.dailyReportJsonEnabled(), enabled
                    );
                    return new CommandResult(updated, "SL400: dailyReportGraphEnabled=" + enabled);
                }
                case "alerthint": {
                    Boolean enabled = parseBoolean(valueRaw);
                    if (enabled == null) return usageMessage(config, fromSet, "alerthint", "true|false");
                    AlertConfig updated = new AlertConfig(
                            config.enabled(), config.thresholdDb(), config.hysteresisDb(),
                            config.minSendIntervalMs(), config.sendMode(), config.metricMode(),
                            config.allowedSenders(), config.commandRoomId(), config.targetRoomId(),
                            enabled, config.dailyReportEnabled(),
                            config.dailyReportHour(), config.dailyReportMinute(), config.dailyReportRoomId(),
                            config.dailyReportJsonEnabled(), config.dailyReportGraphEnabled()
                    );
                    return new CommandResult(updated, "SL400: alertHintFollowupEnabled=" + enabled);
                }
                case "mode": {
                    String value = valueRaw != null ? valueRaw.toLowerCase() : null;
                    SendMode mode = switch (value) {
                        case "crossing" -> SendMode.CROSSING_ONLY;
                        case "periodic" -> SendMode.PERIODIC_WHILE_ABOVE;
                        default -> null;
                    };
                    if (mode == null) return usageMessage(config, fromSet, "mode", "crossing|periodic");
                    AlertConfig updated = new AlertConfig(
                            config.enabled(), config.thresholdDb(), config.hysteresisDb(),
                            config.minSendIntervalMs(), mode, config.metricMode(),
                            config.allowedSenders(), config.commandRoomId(), config.targetRoomId(),
                            config.alertHintFollowupEnabled(), config.dailyReportEnabled(),
                            config.dailyReportHour(), config.dailyReportMinute(), config.dailyReportRoomId(),
                            config.dailyReportJsonEnabled(), config.dailyReportGraphEnabled()
                    );
                    return new CommandResult(updated, "SL400: sendMode=" + mode);
                }
                case "metric": {
                    MetricMode metric = MetricModeParser.parse(valueRaw);
                    if (metric == null) return usageMessage(config, fromSet, "metric", "live|laeq1|laeq5|laeq15|max1");
                    AlertConfig updated = new AlertConfig(
                            config.enabled(), config.thresholdDb(), config.hysteresisDb(),
                            config.minSendIntervalMs(), config.sendMode(), metric,
                            config.allowedSenders(), config.commandRoomId(), config.targetRoomId(),
                            config.alertHintFollowupEnabled(), config.dailyReportEnabled(),
                            config.dailyReportHour(), config.dailyReportMinute(), config.dailyReportRoomId(),
                            config.dailyReportJsonEnabled(), config.dailyReportGraphEnabled()
                    );
                    return new CommandResult(updated, "SL400: metricMode=" + metric);
                }
                case "commandroom": {
                    if (valueRaw == null || valueRaw.isBlank()) {
                        return usageMessage(config, fromSet, "commandroom", "<roomId>");
                    }
                    AlertConfig updated = new AlertConfig(
                            config.enabled(), config.thresholdDb(), config.hysteresisDb(),
                            config.minSendIntervalMs(), config.sendMode(), config.metricMode(),
                            config.allowedSenders(), valueRaw, config.targetRoomId(),
                            config.alertHintFollowupEnabled(), config.dailyReportEnabled(),
                            config.dailyReportHour(), config.dailyReportMinute(), config.dailyReportRoomId(),
                            config.dailyReportJsonEnabled(), config.dailyReportGraphEnabled()
                    );
                    return new CommandResult(updated, "SL400: commandRoomId=" + valueRaw);
                }
                case "targetroom": {
                    if (valueRaw == null || valueRaw.isBlank()) {
                        return usageMessage(config, fromSet, "targetroom", "<roomId>");
                    }
                    AlertConfig updated = new AlertConfig(
                            config.enabled(), config.thresholdDb(), config.hysteresisDb(),
                            config.minSendIntervalMs(), config.sendMode(), config.metricMode(),
                            config.allowedSenders(), config.commandRoomId(), valueRaw,
                            config.alertHintFollowupEnabled(), config.dailyReportEnabled(),
                            config.dailyReportHour(), config.dailyReportMinute(), config.dailyReportRoomId(),
                            config.dailyReportJsonEnabled(), config.dailyReportGraphEnabled()
                    );
                    return new CommandResult(updated, "SL400: targetRoomId=" + valueRaw);
                }
                case "allow": {
                    if (valueRaw == null || valueRaw.isBlank()) {
                        return usageMessage(config, fromSet, "allow", "<@user:server>");
                    }
                    List<String> allowed = config.allowedSenders();
                    if (!allowed.contains(valueRaw)) {
                        allowed = new java.util.ArrayList<>(allowed);
                        allowed.add(valueRaw);
                    }
                    AlertConfig updated = new AlertConfig(
                            config.enabled(), config.thresholdDb(), config.hysteresisDb(),
                            config.minSendIntervalMs(), config.sendMode(), config.metricMode(),
                            List.copyOf(allowed), config.commandRoomId(), config.targetRoomId(),
                            config.alertHintFollowupEnabled(), config.dailyReportEnabled(),
                            config.dailyReportHour(), config.dailyReportMinute(), config.dailyReportRoomId(),
                            config.dailyReportJsonEnabled(), config.dailyReportGraphEnabled()
                    );
                    return new CommandResult(updated,
                            "SL400: allowedSenders=" + String.join(",", updated.allowedSenders()));
                }
                case "deny": {
                    if (valueRaw == null || valueRaw.isBlank()) {
                        return usageMessage(config, fromSet, "deny", "<@user:server>");
                    }
                    List<String> allowed = config.allowedSenders();
                    if (allowed.contains(valueRaw)) {
                        allowed = new java.util.ArrayList<>(allowed);
                        allowed.remove(valueRaw);
                    }
                    AlertConfig updated = new AlertConfig(
                            config.enabled(), config.thresholdDb(), config.hysteresisDb(),
                            config.minSendIntervalMs(), config.sendMode(), config.metricMode(),
                            List.copyOf(allowed), config.commandRoomId(), config.targetRoomId(),
                            config.alertHintFollowupEnabled(), config.dailyReportEnabled(),
                            config.dailyReportHour(), config.dailyReportMinute(), config.dailyReportRoomId(),
                            config.dailyReportJsonEnabled(), config.dailyReportGraphEnabled()
                    );
                    return new CommandResult(updated,
                            "SL400: allowedSenders=" + String.join(",", updated.allowedSenders()));
                }
                default:
                    return null;
            }
        }

        private CommandResult usageMessage(AlertConfig config, boolean fromSet, String key, String hint) {
            String prefix = fromSet ? "!sl400 set " : "!sl400 ";
            return new CommandResult(config, "SL400: Usage `" + prefix + key + " " + hint + "`.");
        }

        private Boolean parseBoolean(String raw) {
            if (raw == null) return null;
            if ("true".equalsIgnoreCase(raw)) return true;
            if ("false".equalsIgnoreCase(raw)) return false;
            return null;
        }

        private Double parseDouble(String raw) {
            if (raw == null) return null;
            try {
                return Double.parseDouble(raw);
            } catch (Exception e) {
                return null;
            }
        }

        private Long parseLong(String raw) {
            if (raw == null) return null;
            try {
                return Long.parseLong(raw);
            } catch (Exception e) {
                return null;
            }
        }

        private int[] parseHourMinute(String value) {
            if (value == null || value.isBlank()) return null;
            String[] parts = value.split(":");
            if (parts.length != 2) return null;
            Integer h = parseInt(parts[0]);
            Integer m = parseInt(parts[1]);
            if (h == null || m == null) return null;
            if (h < 0 || h > 23 || m < 0 || m > 59) return null;
            return new int[]{h, m};
        }

        private Integer parseInt(String raw) {
            if (raw == null) return null;
            try {
                return Integer.parseInt(raw);
            } catch (Exception e) {
                return null;
            }
        }

        private String formatTime(AlertConfig cfg) {
            return String.format("%02d:%02d", cfg.dailyReportHour(), cfg.dailyReportMinute());
        }
    }

    public static class MetricModeParser {
        public static MetricMode parse(String value) {
            if (value == null) return null;
            String v = value.toLowerCase();
            return switch (v) {
                case "live" -> MetricMode.LIVE;
                case "laeq1", "laeq1min", "laeq_1_min" -> MetricMode.LAEQ_1_MIN;
                case "laeq5", "laeq5min", "laeq_5_min" -> MetricMode.LAEQ_5_MIN;
                case "laeq15", "laeq15min", "laeq_15_min" -> MetricMode.LAEQ_15_MIN;
                case "max1", "max1min", "max_1_min" -> MetricMode.MAX_1_MIN;
                default -> null;
            };
        }
    }

    private static class MatrixQueryCommandHandler implements MatrixCommandHandler {
        @Override
        public CommandResult handle(List<String> parts, AlertConfig config) {
            if (parts.size() < 2) return null;
            return switch (parts.get(1).toLowerCase()) {
                case "incidents" -> handleIncidents(parts, config);
                case "summary" -> handleSummary(parts, config);
                default -> null;
            };
        }

        private CommandResult handleIncidents(List<String> parts, AlertConfig config) {
            String mode = parts.size() > 2 ? parts.get(2).toLowerCase() : null;
            if ("since".equals(mode)) {
                String value = parts.size() > 3 ? parts.get(3).toLowerCase() : null;
                if (value == null) {
                    return new CommandResult(config, "SL400: Usage `!sl400 incidents since 30m`.");
                }
                Long durationMs = MatrixCommandParsing.parseDurationMs(value);
                if (durationMs == null) {
                    return new CommandResult(config, "SL400: Invalid duration `" + value + "`.");
                }
                return new CommandResult(config, "SL400: fetching incidents since " + value,
                        new IncidentsSince(durationMs, value));
            }
            if ("between".equals(mode)) {
                String startText = parts.size() > 3 ? parts.get(3) : null;
                String endText = parts.size() > 4 ? parts.get(4) : null;
                if (startText == null || endText == null) {
                    return new CommandResult(config,
                            "SL400: Usage `!sl400 incidents between 2026-03-27T18:00 2026-03-27T23:00`.");
                }
                Long startMs = MatrixCommandParsing.parseDateTimeMs(startText);
                Long endMs = MatrixCommandParsing.parseDateTimeMs(endText);
                if (startMs == null || endMs == null) {
                    return new CommandResult(config, "SL400: Invalid datetime format.");
                }
                if (endMs < startMs) {
                    return new CommandResult(config, "SL400: end must be after start.");
                }
                String label = startText + " to " + endText;
                return new CommandResult(config, "SL400: fetching incidents between " + label,
                        new IncidentsBetween(startMs, endMs, label));
            }
            if ("today".equals(mode)) {
                return new CommandResult(config, "SL400: fetching incidents today", new IncidentsToday());
            }
            if ("yesterday".equals(mode)) {
                return new CommandResult(config, "SL400: fetching incidents yesterday", new IncidentsYesterday());
            }
            return new CommandResult(config, "SL400: Usage `!sl400 incidents since 30m`.");
        }

        private CommandResult handleSummary(List<String> parts, AlertConfig config) {
            String mode = parts.size() > 2 ? parts.get(2).toLowerCase() : null;
            if ("since".equals(mode)) {
                String value = parts.size() > 3 ? parts.get(3).toLowerCase() : null;
                if (value == null) {
                    return new CommandResult(config, "SL400: Usage `!sl400 summary since 1h`.");
                }
                Long durationMs = MatrixCommandParsing.parseDurationMs(value);
                if (durationMs == null) {
                    return new CommandResult(config, "SL400: Invalid duration `" + value + "`.");
                }
                return new CommandResult(config, "SL400: summary since " + value,
                        new SummarySince(durationMs, value));
            }
            if ("today".equals(mode)) {
                return new CommandResult(config, "SL400: summary today", new SummaryToday());
            }
            if ("yesterday".equals(mode)) {
                return new CommandResult(config, "SL400: summary yesterday", new SummaryYesterday());
            }
            return new CommandResult(config, "SL400: Usage `!sl400 summary since 1h`.");
        }
    }

    private static class MatrixExportCommandHandler implements MatrixCommandHandler {
        @Override
        public CommandResult handle(List<String> parts, AlertConfig config) {
            if (parts.size() < 2) return null;
            if (!"json".equalsIgnoreCase(parts.get(1))) return null;
            String mode = parts.size() > 2 ? parts.get(2).toLowerCase() : null;
            if ("since".equals(mode)) {
                String value = parts.size() > 3 ? parts.get(3).toLowerCase() : null;
                if (value == null) return new CommandResult(config, "SL400: Usage `!sl400 json since 24h`.");
                Long durationMs = MatrixCommandParsing.parseDurationMs(value);
                if (durationMs == null) return new CommandResult(config, "SL400: Invalid duration `" + value + "`.");
                return new CommandResult(config, "SL400: exporting json since " + value,
                        new JsonSince(durationMs, value));
            }
            if ("today".equals(mode)) {
                return new CommandResult(config, "SL400: exporting json today", new JsonToday());
            }
            if ("yesterday".equals(mode)) {
                return new CommandResult(config, "SL400: exporting json yesterday", new JsonYesterday());
            }
            return new CommandResult(config, "SL400: Usage `!sl400 json since 24h`.");
        }
    }

    private static class MatrixReportCommandHandler implements MatrixCommandHandler {
        @Override
        public CommandResult handle(List<String> parts, AlertConfig config) {
            if (parts.size() < 2) return null;
            if (!"report".equalsIgnoreCase(parts.get(1))) return null;
            String mode = parts.size() > 2 ? parts.get(2).toLowerCase() : null;
            if (mode == null || "now".equals(mode)) {
                return new CommandResult(config, "SL400: report now", new ReportNow());
            }
            if ("today".equals(mode)) {
                return new CommandResult(config, "SL400: report today", new ReportToday());
            }
            if ("yesterday".equals(mode)) {
                return new CommandResult(config, "SL400: report yesterday", new ReportYesterday());
            }
            if ("since".equals(mode)) {
                String value = parts.size() > 3 ? parts.get(3).toLowerCase() : null;
                if (value == null) return new CommandResult(config, "SL400: Usage `!sl400 report since 24h`.");
                Long durationMs = MatrixCommandParsing.parseDurationMs(value);
                if (durationMs == null) return new CommandResult(config, "SL400: Invalid duration `" + value + "`.");
                return new CommandResult(config, "SL400: report since " + value,
                        new ReportSince(durationMs, value));
            }
            return new CommandResult(config, "SL400: Usage `!sl400 report now|today|yesterday|since <duration>`." );
        }
    }

    private static class MatrixGraphCommandHandler implements MatrixCommandHandler {
        @Override
        public CommandResult handle(List<String> parts, AlertConfig config) {
            if (parts.size() < 2) return null;
            if (!"graph".equalsIgnoreCase(parts.get(1))) return null;
            String mode = parts.size() > 2 ? parts.get(2).toLowerCase() : null;
            if ("since".equals(mode)) {
                String value = parts.size() > 3 ? parts.get(3).toLowerCase() : null;
                if (value == null) return new CommandResult(config, "SL400: Usage `!sl400 graph since 6h`.");
                Long durationMs = MatrixCommandParsing.parseDurationMs(value);
                if (durationMs == null) return new CommandResult(config, "SL400: Invalid duration `" + value + "`.");
                return new CommandResult(config, "SL400: graph since " + value,
                        new GraphSince(durationMs, value));
            }
            if ("today".equals(mode)) {
                return new CommandResult(config, "SL400: graph today", new GraphToday());
            }
            if ("yesterday".equals(mode)) {
                return new CommandResult(config, "SL400: graph yesterday", new GraphYesterday());
            }
            return new CommandResult(config, "SL400: Usage `!sl400 graph since 6h`." );
        }
    }

    private static class MatrixClipCommandHandler implements MatrixCommandHandler {
        @Override
        public CommandResult handle(List<String> parts, AlertConfig config) {
            if (parts.size() < 2) return null;
            String cmd = parts.get(1).toLowerCase();
            if ("clip".equals(cmd)) {
                String mode = parts.size() > 2 ? parts.get(2).toLowerCase() : null;
                if ("last".equals(mode)) {
                    return new CommandResult(config, "SL400: uploading last clip", new ClipLast());
                }
                if ("incident".equals(mode)) {
                    String id = parts.size() > 3 ? parts.get(3) : null;
                    if (id == null || id.isBlank()) {
                        return new CommandResult(config, "SL400: Usage `!sl400 clip incident <incidentId>`." );
                    }
                    return new CommandResult(config, "SL400: uploading clip for incident " + id,
                            new ClipIncident(id));
                }
                return new CommandResult(config, "SL400: Usage `!sl400 clip last`." );
            }
            if ("clips".equals(cmd)) {
                String mode = parts.size() > 2 ? parts.get(2).toLowerCase() : null;
                String value = parts.size() > 3 ? parts.get(3).toLowerCase() : null;
                if (!"since".equals(mode) || value == null) {
                    return new CommandResult(config, "SL400: Usage `!sl400 clips since 2h`." );
                }
                Long durationMs = MatrixCommandParsing.parseDurationMs(value);
                if (durationMs == null) {
                    return new CommandResult(config, "SL400: Invalid duration `" + value + "`." );
                }
                return new CommandResult(config, "SL400: fetching clips since " + value,
                        new ClipsSince(durationMs, value));
            }
            return null;
        }
    }

    private static class MatrixAudioCommandHandler implements MatrixCommandHandler {
        @Override
        public CommandResult handle(List<String> parts, AlertConfig config) {
            if (parts.size() < 2) return null;
            if (!"audio".equalsIgnoreCase(parts.get(1))) return null;
            String mode = parts.size() > 2 ? parts.get(2).toLowerCase() : null;
            if ("start".equals(mode)) {
                return new CommandResult(config, "SL400: audio start requested", new AudioStart());
            }
            if ("stop".equals(mode)) {
                return new CommandResult(config, "SL400: audio stop requested", new AudioStop());
            }
            if ("status".equals(mode)) {
                return new CommandResult(config, "SL400: audio status", new AudioStatus());
            }
            return new CommandResult(config, "SL400: Usage `!sl400 audio start|stop|status`." );
        }
    }

    public static class MatrixCommandParsing {
        public static Long parseDurationMs(String text) {
            if (text == null || text.isBlank()) return null;
            char unit = text.charAt(text.length() - 1);
            String number = text.substring(0, text.length() - 1);
            Long value;
            try {
                value = Long.parseLong(number);
            } catch (Exception e) {
                return null;
            }
            return switch (unit) {
                case 'm' -> value * 60_000L;
                case 'h' -> value * 3_600_000L;
                case 'd' -> value * 86_400_000L;
                default -> null;
            };
        }

        public static Long parseDateTimeMs(String text) {
            String[] patterns = new String[]{
                    "yyyy-MM-dd'T'HH:mm",
                    "yyyy-MM-dd'T'HH:mm:ss",
                    "yyyy-MM-dd HH:mm",
                    "yyyy-MM-dd HH:mm:ss"
            };
            for (String pattern : patterns) {
                try {
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(pattern, java.util.Locale.US);
                    sdf.setLenient(false);
                    sdf.setTimeZone(java.util.TimeZone.getDefault());
                    java.util.Date date = sdf.parse(text);
                    if (date != null) return date.getTime();
                } catch (Exception ignored) {
                }
            }
            return null;
        }
    }
}
