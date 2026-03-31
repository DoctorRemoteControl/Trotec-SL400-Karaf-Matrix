package de.drremote.trotecsl400.serial;

import com.fazecast.jSerialComm.SerialPort;
import de.drremote.trotecsl400.api.Sl400Sample;
import de.drremote.trotecsl400.api.Sl400SampleListener;
import de.drremote.trotecsl400.api.Sl400Source;
import de.drremote.trotecsl400.api.LiveStateService;
import de.drremote.trotecsl400.core.Sl400Decoder;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component(service = Sl400Source.class, configurationPid = "de.drremote.trotecsl400.serial")
@Designate(ocd = Sl400SerialSource.Config.class)
public class Sl400SerialSource implements Sl400Source {
    private static final Logger LOG = LoggerFactory.getLogger(Sl400SerialSource.class);

    @ObjectClassDefinition(name = "Trotec SL400 Serial Configuration")
    public @interface Config {
        @AttributeDefinition
        String port() default "AUTO";

        @AttributeDefinition
        int baudRate() default 9600;

        @AttributeDefinition
        int dataBits() default 8;

        @AttributeDefinition
        int stopBits() default 1;

        @AttributeDefinition
        String parity() default "NONE";

        @AttributeDefinition
        int readTimeoutMs() default 1000;

        @AttributeDefinition
        int reconnectDelayMs() default 5000;

        @AttributeDefinition
        boolean autoDetect() default true;

        @AttributeDefinition
        String preferredPortHint() default "";

        @AttributeDefinition
        int probeTimeoutMs() default 1500;

        @AttributeDefinition
        int minSamplesForAutoDetect() default 2;

        @AttributeDefinition
        boolean enabled() default true;

        @AttributeDefinition
        boolean logSamples() default false;

        @AttributeDefinition
        long sampleLogIntervalMs() default 1000L;
    }

    private volatile Sl400SampleListener listener;
    private volatile Config config;
    private final Object lock = new Object();
    private volatile boolean running = false;
    private volatile Thread readThread;
    private volatile SerialPort portHandle;
    private final Sl400Decoder decoder = new Sl400Decoder();
    private volatile long lastSampleLogAtMs = 0L;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    private volatile LiveStateService liveStateService;

    @Override
    public void setListener(Sl400SampleListener listener) {
        this.listener = listener;
    }

    @Activate
    @Modified
    void activate(Config cfg) {
        this.config = cfg;
        this.lastSampleLogAtMs = 0L;
        if (running) {
            restart();
        }
    }

    @Override
    public void start() {
        Thread threadToStart;
        synchronized (lock) {
            if (running) {
                return;
            }
            if (config == null || !config.enabled()) {
                LOG.info("Serial source disabled by config");
                return;
            }
            running = true;
            readThread = new Thread(this::runLoop, "sl400-serial-read");
            readThread.setDaemon(true);
            threadToStart = readThread;
        }
        threadToStart.start();
    }

    @Override
    public void stop() {
        Thread threadToJoin;
        synchronized (lock) {
            running = false;
            threadToJoin = readThread;
            readThread = null;
            closePort();
            if (threadToJoin != null) {
                threadToJoin.interrupt();
            }
        }

        if (threadToJoin != null && threadToJoin != Thread.currentThread()) {
            try {
                threadToJoin.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void restart() {
        stop();
        start();
    }

    private void runLoop() {
        final Thread currentThread = Thread.currentThread();

        while (running && readThread == currentThread) {
            Config cfg = this.config;
            if (cfg == null || !cfg.enabled()) {
                sleepQuiet(1000);
                continue;
            }
            try {
                openPort(cfg);
                readFromPort(cfg);
            } catch (Exception e) {
                LOG.warn("Serial read loop error: {}", e.getMessage());
                recordError("serial error: " + e.getMessage());
            } finally {
                closePort();
            }
            if (running && readThread == currentThread) {
                sleepQuiet(cfg.reconnectDelayMs());
            }
        }
    }

    private void openPort(Config cfg) {
        Exception directFailure = null;
        String configuredPort = normalize(cfg.port());

        if (isExplicitPort(configuredPort)) {
            try {
                SerialPort explicit = SerialPort.getCommPort(configuredPort);
                openConfiguredPort(explicit, cfg, cfg.readTimeoutMs());
                portHandle = explicit;
                decoder.reset();
                LOG.info("Serial port opened (configured): {}", describePort(explicit));
                return;
            } catch (Exception e) {
                directFailure = e;
                LOG.warn("Configured serial port {} could not be opened: {}", configuredPort, e.getMessage());
            }
        }

        if (!cfg.autoDetect()) {
            if (directFailure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (directFailure != null) {
                throw new IllegalStateException("Failed to open configured serial port", directFailure);
            }
            throw new IllegalStateException("Serial auto-detect is disabled and no usable port is configured");
        }

        SerialPort detected = detectPort(cfg);
        if (detected == null) {
            if (directFailure != null) {
                throw new IllegalStateException("Configured port failed and auto-detect found no SL400 device", directFailure);
            }
            throw new IllegalStateException("No SL400-compatible serial port detected");
        }

        openConfiguredPort(detected, cfg, cfg.readTimeoutMs());
        portHandle = detected;
        decoder.reset();
        LOG.info("Serial port opened (auto-detected): {}", describePort(detected));
    }

    private void openConfiguredPort(SerialPort sp, Config cfg, int readTimeoutMs) {
        configurePort(sp, cfg, readTimeoutMs);
        sp.clearDTR();
        sp.clearRTS();
        if (!sp.openPort()) {
            throw new IllegalStateException("Failed to open serial port " + safe(sp.getSystemPortName()));
        }
        sp.clearDTR();
        sp.clearRTS();
    }

    private void configurePort(SerialPort sp, Config cfg, int readTimeoutMs) {
        sp.setComPortParameters(
                cfg.baudRate(),
                cfg.dataBits(),
                toStopBits(cfg.stopBits()),
                toParity(cfg.parity())
        );
        sp.setComPortTimeouts(
                SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                Math.max(0, readTimeoutMs),
                0
        );
    }

    private SerialPort detectPort(Config cfg) {
        List<PortCandidate> candidates = rankCandidates(cfg);
        if (candidates.isEmpty()) {
            LOG.warn("No serial ports available for auto-detect");
            return null;
        }

        for (PortCandidate candidate : candidates) {
            SerialPort port = candidate.port();
            LOG.info("Probing serial candidate: {}", describePort(port));
            if (probeCandidate(port, cfg)) {
                LOG.info("Auto-detect matched SL400 on {}", describePort(port));
                return port;
            }
        }
        return null;
    }

    private List<PortCandidate> rankCandidates(Config cfg) {
        List<PortCandidate> out = new ArrayList<>();
        String hint = normalize(cfg.preferredPortHint());
        for (SerialPort port : SerialPort.getCommPorts()) {
            out.add(new PortCandidate(port, scorePort(port, hint)));
        }
        out.sort(Comparator.comparingInt(PortCandidate::score).reversed());
        return out;
    }

    private int scorePort(SerialPort port, String hint) {
        int score = 0;

        int vid = port.getVendorID();
        int pid = port.getProductID();
        if (vid == 0x10C4 && pid == 0xEA60) {
            score += 100;
        }

        if (containsIgnoreCase(port.getManufacturer(), "Silicon Labs")) {
            score += 40;
        }
        if (containsIgnoreCase(port.getPortDescription(), "CP210", "USB", "UART")) {
            score += 30;
        }
        if (containsIgnoreCase(port.getDescriptivePortName(), "CP210", "Silicon Labs", "USB", "UART")) {
            score += 20;
        }

        if (hint != null && !hint.isBlank() && (
                containsIgnoreCase(port.getSystemPortName(), hint) ||
                        containsIgnoreCase(port.getSystemPortPath(), hint) ||
                        containsIgnoreCase(port.getPortLocation(), hint) ||
                        containsIgnoreCase(port.getPortDescription(), hint) ||
                        containsIgnoreCase(port.getDescriptivePortName(), hint) ||
                        containsIgnoreCase(port.getSerialNumber(), hint) ||
                        containsIgnoreCase(port.getManufacturer(), hint))) {
            score += 1000;
        }

        return score;
    }

    private boolean probeCandidate(SerialPort port, Config cfg) {
        byte[] buffer = new byte[256];
        Sl400Decoder probeDecoder = new Sl400Decoder();
        int sampleCount = 0;
        long deadline = System.currentTimeMillis() + Math.max(300, cfg.probeTimeoutMs());

        try {
            openConfiguredPort(port, cfg, Math.min(Math.max(100, cfg.readTimeoutMs()), 250));
            while (running && System.currentTimeMillis() < deadline) {
                int len = port.readBytes(buffer, buffer.length);
                if (len <= 0) {
                    continue;
                }
                byte[] chunk = new byte[len];
                System.arraycopy(buffer, 0, chunk, 0, len);
                sampleCount += probeDecoder.feed(chunk).size();
                if (sampleCount >= Math.max(1, cfg.minSamplesForAutoDetect())) {
                    return true;
                }
            }
        } catch (Exception e) {
            LOG.debug("Probe failed for {}: {}", safe(port.getSystemPortName()), e.getMessage());
        } finally {
            try {
                if (port.isOpen()) {
                    port.closePort();
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private void readFromPort(Config cfg) {
        SerialPort sp = portHandle;
        if (sp == null) {
            throw new IllegalStateException("Serial port not opened");
        }
        byte[] buffer = new byte[256];
        while (running && sp.isOpen()) {
            int len = sp.readBytes(buffer, buffer.length);
            if (len > 0) {
                byte[] chunk = new byte[len];
                System.arraycopy(buffer, 0, chunk, 0, len);
                List<Sl400Sample> samples = decoder.feed(chunk);
                Sl400SampleListener l = listener;
                for (Sl400Sample s : samples) {
                    maybeLogSample(s, cfg);
                    if (l != null) {
                        l.onSample(s);
                    }
                }
            }
        }
    }

    private void maybeLogSample(Sl400Sample sample, Config cfg) {
        if (sample == null || cfg == null || !cfg.logSamples()) {
            return;
        }
        long now = sample.timestampMs();
        long interval = Math.max(0L, cfg.sampleLogIntervalMs());
        if (interval > 0L && (now - lastSampleLogAtMs) < interval) {
            return;
        }
        lastSampleLogAtMs = now;

        LOG.info(
                "SL400 sample: {} dB | raw={} | aux06={} | tags={}",
                String.format(Locale.US, "%.1f", sample.db()),
                sample.rawTenths(),
                sample.aux06Hex() == null || sample.aux06Hex().isBlank() ? "-" : sample.aux06Hex(),
                sample.tags()
        );
    }

    private void closePort() {
        SerialPort sp = portHandle;
        portHandle = null;
        if (sp != null && sp.isOpen()) {
            sp.closePort();
            LOG.info("Serial port closed");
        }
    }

    private boolean isExplicitPort(String port) {
        return port != null && !port.isBlank() && !"AUTO".equalsIgnoreCase(port);
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }

    private boolean containsIgnoreCase(String text, String... needles) {
        if (text == null || text.isBlank() || needles == null) {
            return false;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (needle != null && !needle.isBlank()
                    && haystack.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String describePort(SerialPort port) {
        return safe(port.getSystemPortName())
                + " path=" + safe(port.getSystemPortPath())
                + " location=" + safe(port.getPortLocation())
                + " vid=" + toHex(port.getVendorID())
                + " pid=" + toHex(port.getProductID())
                + " serial=" + safe(port.getSerialNumber())
                + " manufacturer=" + safe(port.getManufacturer())
                + " desc=" + safe(port.getPortDescription());
    }

    private String toHex(int value) {
        return value < 0 ? "n/a" : String.format("0x%04X", value);
    }

    private String safe(String value) {
        return (value == null || value.isBlank()) ? "-" : value;
    }

    private record PortCandidate(SerialPort port, int score) {
    }

    private int toParity(String parity) {
        if (parity == null) return SerialPort.NO_PARITY;
        String p = parity.trim().toUpperCase(Locale.ROOT);
        return switch (p) {
            case "ODD" -> SerialPort.ODD_PARITY;
            case "EVEN" -> SerialPort.EVEN_PARITY;
            case "MARK" -> SerialPort.MARK_PARITY;
            case "SPACE" -> SerialPort.SPACE_PARITY;
            default -> SerialPort.NO_PARITY;
        };
    }

    private int toStopBits(int stopBits) {
        return switch (stopBits) {
            case 2 -> SerialPort.TWO_STOP_BITS;
            case 3 -> SerialPort.ONE_POINT_FIVE_STOP_BITS;
            default -> SerialPort.ONE_STOP_BIT;
        };
    }

    private void sleepQuiet(int ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void recordError(String message) {
        LiveStateService liveState = this.liveStateService;
        if (liveState != null && message != null && !message.isBlank()) {
            liveState.recordError(message);
        }
    }
}
