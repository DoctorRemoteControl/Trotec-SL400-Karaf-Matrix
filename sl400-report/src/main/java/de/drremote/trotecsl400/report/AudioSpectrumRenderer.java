package de.drremote.trotecsl400.report;

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
import java.util.Locale;

public final class AudioSpectrumRenderer {
    private static final int WIDTH = 1200;
    private static final int HEIGHT = 600;
    private static final int LEFT = 80;
    private static final int RIGHT = 30;
    private static final int TOP = 40;
    private static final int BOTTOM = 70;

    private AudioSpectrumRenderer() {
    }

    public static Path renderSpectrumPng(SpectrumResult spectrum, String label) throws IOException {
        String safeLabel = sanitizeLabel(label);
        Path file = Files.createTempFile("sl400_fft_" + safeLabel + "_", ".png");
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, WIDTH, HEIGHT);

            if (spectrum == null || spectrum.frequenciesHz().length == 0) {
                drawEmpty(g, label);
                ImageIO.write(image, "png", file.toFile());
                return file;
            }

            int plotW = WIDTH - LEFT - RIGHT;
            int plotH = HEIGHT - TOP - BOTTOM;

            double minDb = min(spectrum.magnitudesDb());
            double maxDb = max(spectrum.magnitudesDb());
            if (maxDb - minDb < 6.0) {
                double mid = (maxDb + minDb) / 2.0;
                minDb = mid - 3.0;
                maxDb = mid + 3.0;
            }
            minDb -= 3.0;
            maxDb += 3.0;

            drawAxes(g, minDb, maxDb, plotW, plotH, spectrum.sampleRate());
            drawSpectrumLine(g, spectrum, minDb, maxDb, plotW, plotH);
            drawTitle(g, label, spectrum);
        } finally {
            g.dispose();
        }

        ImageIO.write(image, "png", file.toFile());
        return file;
    }

    private static void drawEmpty(Graphics2D g, String label) {
        g.setColor(Color.DARK_GRAY);
        g.setFont(new Font("SansSerif", Font.PLAIN, 18));
        String text = "SL400 FFT (" + (label == null ? "clip" : label) + "): no data.";
        FontMetrics fm = g.getFontMetrics();
        int x = (WIDTH - fm.stringWidth(text)) / 2;
        int y = HEIGHT / 2;
        g.drawString(text, x, y);
    }

    private static void drawAxes(Graphics2D g, double minDb, double maxDb,
                                 int plotW, int plotH, int sampleRate) {
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
            double val = maxDb - (i * (maxDb - minDb) / gridLines);
            String label = String.format(Locale.US, "%.1f dB", val);
            int y = TOP + (int) Math.round(i * (plotH / (double) gridLines));
            g.drawString(label, 10, y + 4);
        }

        int nyquist = sampleRate / 2;
        int xTicks = 6;
        for (int i = 0; i <= xTicks; i++) {
            int x = LEFT + (int) Math.round(i * (plotW / (double) xTicks));
            g.setColor(new Color(230, 230, 230));
            g.drawLine(x, TOP, x, TOP + plotH);
            g.setColor(Color.BLACK);
            String label = String.format(Locale.US, "%.0f Hz", i * (nyquist / (double) xTicks));
            int lw = g.getFontMetrics().stringWidth(label);
            g.drawString(label, x - lw / 2, TOP + plotH + 30);
        }
    }

    private static void drawSpectrumLine(Graphics2D g, SpectrumResult spectrum,
                                         double minDb, double maxDb, int plotW, int plotH) {
        Path2D path = new Path2D.Double();
        double[] freqs = spectrum.frequenciesHz();
        double[] mags = spectrum.magnitudesDb();
        for (int i = 0; i < freqs.length; i++) {
            double x = LEFT + (freqs[i] / (spectrum.sampleRate() / 2.0)) * plotW;
            double y = toY(mags[i], minDb, maxDb, plotH);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        g.setColor(new Color(30, 90, 200));
        g.setStroke(new BasicStroke(2f));
        g.draw(path);
        g.setStroke(new BasicStroke(1f));
    }

    private static void drawTitle(Graphics2D g, String label, SpectrumResult spectrum) {
        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        String title = "SL400 FFT Spectrum - " + (label == null ? "clip" : label);
        g.drawString(title, LEFT, 25);

        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        String info = String.format(
                Locale.US,
                "sampleRate=%d Hz, fftSize=%d, dominant=%.0f Hz, centroid=%.0f Hz",
                spectrum.sampleRate(),
                spectrum.fftSize(),
                spectrum.dominantFrequencyHz(),
                spectrum.spectralCentroidHz()
        );
        g.drawString(info, LEFT, 40);
    }

    private static int toY(double value, double minVal, double maxVal, int plotH) {
        double norm = (maxVal - value) / (maxVal - minVal);
        return TOP + (int) Math.round(norm * plotH);
    }

    private static double min(double[] values) {
        double min = Double.POSITIVE_INFINITY;
        for (double v : values) {
            min = Math.min(min, v);
        }
        return Double.isFinite(min) ? min : 0.0;
    }

    private static double max(double[] values) {
        double max = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            max = Math.max(max, v);
        }
        return Double.isFinite(max) ? max : 0.0;
    }

    private static String sanitizeLabel(String label) {
        String raw = (label == null || label.isBlank()) ? "clip" : label;
        String safe = raw.replaceAll("[^a-zA-Z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        if (safe.length() < 3) {
            return "clip";
        }
        return safe;
    }
}
