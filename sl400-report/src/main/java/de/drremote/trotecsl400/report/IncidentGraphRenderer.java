package de.drremote.trotecsl400.report;

import de.drremote.trotecsl400.api.AlertConfig;
import de.drremote.trotecsl400.api.IncidentRecord;
import de.drremote.trotecsl400.api.MetricMode;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class IncidentGraphRenderer {
    private static final int WIDTH = 1200;
    private static final int HEIGHT = 600;
    private static final int LEFT = 80;
    private static final int RIGHT = 30;
    private static final int TOP = 40;
    private static final int BOTTOM = 70;

    private IncidentGraphRenderer() {
    }

    public static Path renderGraph(List<IncidentRecord> incidents, AlertConfig alertCfg, String label)
            throws IOException {
        String safeLabel = sanitizeLabel(label);
        Path file = Files.createTempFile("sl400_graph_" + safeLabel + "_", ".png");
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, WIDTH, HEIGHT);

            if (incidents == null || incidents.isEmpty()) {
                drawEmpty(g, label);
                ImageIO.write(image, "png", file.toFile());
                return file;
            }

            List<IncidentRecord> sorted = new ArrayList<>(incidents);
            sorted.sort(Comparator.comparingLong(IncidentRecord::timestampMs));

            long minTs = sorted.get(0).timestampMs();
            long maxTs = sorted.get(sorted.size() - 1).timestampMs();
            if (maxTs <= minTs) {
                minTs -= 30_000L;
                maxTs += 30_000L;
            } else if (maxTs - minTs < 60_000L) {
                long pad = (60_000L - (maxTs - minTs)) / 2L;
                minTs -= pad;
                maxTs += pad;
            }

            GraphMeta meta = buildGraphMeta(sorted, alertCfg);
            double threshold = meta.thresholdDb();
            double hyst = meta.hysteresisDb();

            double minVal = threshold - hyst;
            double maxVal = threshold;
            for (IncidentRecord r : sorted) {
                if (r.metricValue() != null) {
                    minVal = Math.min(minVal, r.metricValue());
                    maxVal = Math.max(maxVal, r.metricValue());
                }
                if (r.laEq5Min() != null) {
                    minVal = Math.min(minVal, r.laEq5Min());
                    maxVal = Math.max(maxVal, r.laEq5Min());
                }
            }
            if (maxVal - minVal < 5.0) {
                double mid = (maxVal + minVal) / 2.0;
                minVal = mid - 2.5;
                maxVal = mid + 2.5;
            }
            minVal -= 3.0;
            maxVal += 3.0;

            int plotW = WIDTH - LEFT - RIGHT;
            int plotH = HEIGHT - TOP - BOTTOM;

            drawAxes(g, minVal, maxVal, minTs, maxTs, plotW, plotH);
            drawThresholds(g, meta.thresholdDb(), meta.resetDb(), minVal, maxVal, plotW, plotH);
            drawSeries(g, sorted, minVal, maxVal, minTs, maxTs, plotW, plotH, meta);

            drawTitle(g, label, meta);
            drawLegend(g, meta);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", file.toFile());
        return file;
    }

    private static void drawEmpty(Graphics2D g, String label) {
        g.setColor(Color.DARK_GRAY);
        g.setFont(new Font("SansSerif", Font.PLAIN, 18));
        String text = "SL400 graph (" + (label == null ? "all" : label) + "): no incidents.";
        FontMetrics fm = g.getFontMetrics();
        int x = (WIDTH - fm.stringWidth(text)) / 2;
        int y = HEIGHT / 2;
        g.drawString(text, x, y);
    }

    private static void drawAxes(Graphics2D g, double minVal, double maxVal,
                                 long minTs, long maxTs, int plotW, int plotH) {
        g.setColor(new Color(220, 220, 220));
        int gridLines = 5;
        for (int i = 0; i <= gridLines; i++) {
            int y = TOP + (int) Math.round(i * (plotH / (double) gridLines));
            g.drawLine(LEFT, y, LEFT + plotW, y);
        }
        g.setColor(Color.BLACK);
        g.drawLine(LEFT, TOP, LEFT, TOP + plotH);
        g.drawLine(LEFT, TOP + plotH, LEFT + plotW, TOP + plotH);

        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        for (int i = 0; i <= gridLines; i++) {
            double val = maxVal - (i * (maxVal - minVal) / gridLines);
            String label = String.format(Locale.US, "%.1f", val);
            int y = TOP + (int) Math.round(i * (plotH / (double) gridLines));
            g.drawString(label, 10, y + 4);
        }

        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
        String leftLabel = fmt.format(new Date(minTs));
        String rightLabel = fmt.format(new Date(maxTs));
        g.drawString(leftLabel, LEFT, TOP + plotH + 30);
        int rightX = LEFT + plotW - g.getFontMetrics().stringWidth(rightLabel);
        g.drawString(rightLabel, rightX, TOP + plotH + 30);
    }

    private static void drawThresholds(Graphics2D g, double threshold, double hysteresisLine,
                                       double minVal, double maxVal, int plotW, int plotH) {
        int yThreshold = toY(threshold, minVal, maxVal, plotH);
        int yHyst = toY(hysteresisLine, minVal, maxVal, plotH);

        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(200, 0, 0));
        g.drawLine(LEFT, yThreshold, LEFT + plotW, yThreshold);

        g.setColor(new Color(240, 170, 0));
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0,
                new float[]{6f, 6f}, 0));
        g.drawLine(LEFT, yHyst, LEFT + plotW, yHyst);
        g.setStroke(new BasicStroke(1f));
    }

    private static void drawSeries(Graphics2D g, List<IncidentRecord> incidents,
                                   double minVal, double maxVal, long minTs, long maxTs,
                                   int plotW, int plotH, GraphMeta meta) {
        Path2D metricPath = new Path2D.Double();
        boolean metricStarted = false;

        Path2D laeqPath = new Path2D.Double();
        boolean laeqStarted = false;

        for (IncidentRecord r : incidents) {
            double x = toX(r.timestampMs(), minTs, maxTs, plotW);
            if (r.metricValue() != null && matchesMetricMode(r, meta.currentMetricMode())) {
                double y = toY(r.metricValue(), minVal, maxVal, plotH);
                if (!metricStarted) {
                    metricPath.moveTo(x, y);
                    metricStarted = true;
                } else {
                    metricPath.lineTo(x, y);
                }
            }
            if (meta.showLaeq5() && r.laEq5Min() != null) {
                double y = toY(r.laEq5Min(), minVal, maxVal, plotH);
                if (!laeqStarted) {
                    laeqPath.moveTo(x, y);
                    laeqStarted = true;
                } else {
                    laeqPath.lineTo(x, y);
                }
            }
        }

        if (metricStarted) {
            g.setStroke(new BasicStroke(2f));
            g.setColor(new Color(30, 90, 200));
            g.draw(metricPath);
        }
        if (meta.showLaeq5() && laeqStarted) {
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0,
                    new float[]{5f, 5f}, 0));
            g.setColor(new Color(0, 140, 80));
            g.draw(laeqPath);
        }

        g.setStroke(new BasicStroke(1f));
        for (IncidentRecord r : incidents) {
            if (r.metricValue() == null || !matchesMetricMode(r, meta.currentMetricMode())) {
                continue;
            }
            double x = toX(r.timestampMs(), minTs, maxTs, plotW);
            double y = toY(r.metricValue(), minVal, maxVal, plotH);
            Color dot = severityColor(r.metricValue(), meta.thresholdDb(), meta.hysteresisDb());
            g.setColor(dot);
            g.fillOval((int) Math.round(x) - 3, (int) Math.round(y) - 3, 6, 6);
        }
    }

    private static void drawTitle(Graphics2D g, String label, GraphMeta meta) {
        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        String title = "SL400 Incidents Graph - " + (label == null ? "all" : label)
                + " | metric: " + meta.currentMetricLabel();
        g.drawString(title, LEFT, 25);

        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        String text = String.format(Locale.US,
                "threshold=%.1f dB, hysteresis=%.1f dB",
                meta.thresholdDb(), meta.hysteresisDb());
        if (meta.mixedConfigInRange()) {
            text += " | mixed config in range, latest settings shown";
        }
        g.drawString(text, LEFT, 40);
    }

    private static void drawLegend(Graphics2D g, GraphMeta meta) {
        int x = WIDTH - RIGHT - 300;
        int y = TOP + 10;
        int h = meta.mixedConfigInRange() ? 110 : 92;

        g.setColor(new Color(245, 245, 245));
        g.fillRect(x, y, 290, h);
        g.setColor(Color.GRAY);
        g.drawRect(x, y, 290, h);

        g.setFont(new Font("SansSerif", Font.PLAIN, 12));

        int y1 = y + 18;
        g.setColor(new Color(30, 90, 200));
        g.drawLine(x + 10, y1, x + 40, y1);
        g.setColor(Color.BLACK);
        g.drawString(meta.currentMetricLabel(), x + 50, y1 + 4);

        int y2 = y + 36;
        if (meta.showLaeq5()) {
            g.setColor(new Color(0, 140, 80));
            g.drawLine(x + 10, y2, x + 40, y2);
            g.setColor(Color.BLACK);
            g.drawString("LAeq 5 min", x + 50, y2 + 4);
        }

        int y3 = meta.showLaeq5() ? y + 54 : y + 36;
        g.setColor(new Color(200, 0, 0));
        g.drawLine(x + 10, y3, x + 40, y3);
        g.setColor(Color.BLACK);
        g.drawString(String.format(Locale.US, "threshold %.1f dB", meta.thresholdDb()), x + 50, y3 + 4);

        int y4 = y + 72;
        g.setColor(new Color(240, 170, 0));
        g.drawLine(x + 10, y4, x + 40, y4);
        g.setColor(Color.BLACK);
        g.drawString(String.format(Locale.US, "reset %.1f dB", meta.resetDb()), x + 50, y4 + 4);

        if (meta.mixedConfigInRange()) {
            g.setColor(Color.BLACK);
            g.drawString("mixed config in selected range", x + 10, y + 94);
        }
    }

    private static int toY(double value, double minVal, double maxVal, int plotH) {
        double norm = (maxVal - value) / (maxVal - minVal);
        return TOP + (int) Math.round(norm * plotH);
    }

    private static double toX(long ts, long minTs, long maxTs, int plotW) {
        double norm = (ts - minTs) / (double) (maxTs - minTs);
        return LEFT + norm * plotW;
    }

    private static Color severityColor(double value, double threshold, double hysteresis) {
        if (value >= threshold) {
            return new Color(200, 0, 0);
        }
        if (value >= threshold - hysteresis) {
            return new Color(240, 170, 0);
        }
        return new Color(50, 120, 200);
    }

    private static String sanitizeLabel(String label) {
        String raw = (label == null || label.isBlank()) ? "all" : label;
        String safe = raw.replaceAll("[^a-zA-Z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        if (safe.length() < 3) {
            return "all";
        }
        return safe;
    }

    private static GraphMeta buildGraphMeta(List<IncidentRecord> incidents, AlertConfig cfg) {
        MetricMode currentMetricMode = cfg != null ? cfg.metricMode() : null;
        String currentMetricLabel = currentMetricMode != null
                ? IncidentFormatting.formatMetricMode(currentMetricMode)
                : "metric value";

        double thresholdDb = cfg != null ? cfg.thresholdDb() : 70.0;
        double hysteresisDb = cfg != null ? cfg.hysteresisDb() : 2.0;
        if (incidents != null && !incidents.isEmpty()) {
            IncidentRecord latest = incidents.get(incidents.size() - 1);
            thresholdDb = latest.thresholdDb();
            if (latest.hysteresisDb() != null) {
                hysteresisDb = latest.hysteresisDb();
            }
            if (currentMetricMode == null && latest.metricMode() != null) {
                try {
                    currentMetricMode = MetricMode.valueOf(latest.metricMode());
                    currentMetricLabel = IncidentFormatting.formatMetricMode(currentMetricMode);
                } catch (Exception ignored) {
                }
            }
        }

        double resetDb = thresholdDb - hysteresisDb;
        boolean showLaeq5 = incidents.stream().anyMatch(i -> i.laEq5Min() != null);

        Set<String> metricModes = new LinkedHashSet<>();
        Set<Double> thresholds = new LinkedHashSet<>();
        Set<Double> hysteresis = new LinkedHashSet<>();
        for (IncidentRecord incident : incidents) {
            if (incident.metricMode() != null && !incident.metricMode().isBlank()) {
                metricModes.add(incident.metricMode());
            }
            thresholds.add(incident.thresholdDb());
            if (incident.hysteresisDb() != null) {
                hysteresis.add(incident.hysteresisDb());
            }
        }

        boolean mixedMetricModes = metricModes.size() > 1;
        boolean mixedThresholds = thresholds.size() > 1;
        boolean mixedHysteresis = hysteresis.size() > 1;

        return new GraphMeta(
                currentMetricMode,
                currentMetricLabel,
                thresholdDb,
                hysteresisDb,
                resetDb,
                showLaeq5,
                mixedMetricModes || mixedThresholds || mixedHysteresis
        );
    }

    private static boolean matchesMetricMode(IncidentRecord record, MetricMode currentMetricMode) {
        if (record == null || record.metricMode() == null || record.metricMode().isBlank()) {
            return false;
        }
        if (currentMetricMode == null) {
            return true;
        }
        return currentMetricMode.name().equals(record.metricMode());
    }

    private record GraphMeta(
            MetricMode currentMetricMode,
            String currentMetricLabel,
            double thresholdDb,
            double hysteresisDb,
            double resetDb,
            boolean showLaeq5,
            boolean mixedConfigInRange
    ) {
    }
}
