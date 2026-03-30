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

import java.util.List;
import java.util.Locale;

@Component(service = Sl400Source.class, configurationPid = "de.drremote.trotecsl400.serial")
@Designate(ocd = Sl400SerialSource.Config.class)
public class Sl400SerialSource implements Sl400Source {
    private static final Logger LOG = LoggerFactory.getLogger(Sl400SerialSource.class);

    @ObjectClassDefinition(name = "Trotec SL400 Serial Configuration")
    public @interface Config {
        @AttributeDefinition
        String port() default "/dev/ttyUSB0";

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
        boolean enabled() default true;
    }

    private volatile Sl400SampleListener listener;
    private volatile Config config;
    private final Object lock = new Object();
    private volatile boolean running = false;
    private volatile Thread readThread;
    private volatile SerialPort portHandle;
    private final Sl400Decoder decoder = new Sl400Decoder();

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
        if (running) {
            restart();
        }
    }

    @Override
    public void start() {
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
            readThread.start();
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            running = false;
            closePort();
            if (readThread != null) {
                readThread.interrupt();
            }
        }
    }

    private void restart() {
        stop();
        start();
    }

    private void runLoop() {
        while (running) {
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
            if (running) {
                sleepQuiet(cfg.reconnectDelayMs());
            }
        }
    }

    private void openPort(Config cfg) {
        String portName = cfg.port();
        SerialPort sp = SerialPort.getCommPort(portName);
        sp.setComPortParameters(
                cfg.baudRate(),
                cfg.dataBits(),
                toStopBits(cfg.stopBits()),
                toParity(cfg.parity())
        );
        sp.setComPortTimeouts(
                SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                Math.max(0, cfg.readTimeoutMs()),
                0
        );
        if (!sp.openPort()) {
            throw new IllegalStateException("Failed to open serial port " + portName);
        }
        portHandle = sp;
        decoder.reset();
        LOG.info("Serial port opened: {}", portName);
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
                if (l != null) {
                    for (Sl400Sample s : samples) {
                        l.onSample(s);
                    }
                }
            }
        }
    }

    private void closePort() {
        SerialPort sp = portHandle;
        portHandle = null;
        if (sp != null && sp.isOpen()) {
            sp.closePort();
            LOG.info("Serial port closed");
        }
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
