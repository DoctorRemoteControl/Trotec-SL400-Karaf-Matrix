package de.drremote.trotecsl400.runtime;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component(service = AudioCaptureService.class, configurationPid = "de.drremote.trotecsl400.audio")
@Designate(ocd = AudioCaptureService.Config.class)
public class AudioCaptureService {
    private static final Logger LOG = LoggerFactory.getLogger(AudioCaptureService.class);

    @ObjectClassDefinition(name = "Trotec SL400 Audio Capture")
    public @interface Config {
        @AttributeDefinition
        String baseDir() default "${karaf.data}/sl400";

        @AttributeDefinition
        String clipsDirName() default "clips";

        @AttributeDefinition
        int sampleRate() default 16000;

        @AttributeDefinition
        int channels() default 1;

        @AttributeDefinition
        int sampleSizeBits() default 16;

        @AttributeDefinition
        int bufferSeconds() default 30;

        @AttributeDefinition
        int preRollMs() default 10000;

        @AttributeDefinition
        int postRollMs() default 20000;

        @AttributeDefinition
        String preferredMixerName() default "";

        @AttributeDefinition
        boolean autoStart() default false;
    }

    private final Object lock = new Object();
    private final ExecutorService clipExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sl400-clip-writer");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running;
    private volatile TargetDataLine line;
    private volatile Thread captureThread;
    private volatile AudioRingBuffer ringBuffer;
    private volatile AudioFormat format;
    private volatile int preRollMs;
    private volatile int postRollMs;
    private volatile Path clipsDir;
    private volatile String lastError;
    private volatile String preferredMixerName;

    @Activate
    @Modified
    void activate(Config cfg) {
        String base = resolveBaseDir(cfg.baseDir());
        clipsDir = Path.of(base, cfg.clipsDirName());
        preRollMs = cfg.preRollMs();
        postRollMs = cfg.postRollMs();
        preferredMixerName = cfg.preferredMixerName();
        format = new AudioFormat(
                cfg.sampleRate(),
                cfg.sampleSizeBits(),
                cfg.channels(),
                true,
                false
        );
        int bytesPerSecond = cfg.sampleRate() * cfg.channels() * (cfg.sampleSizeBits() / 8);
        int capacity = Math.max(1, bytesPerSecond * cfg.bufferSeconds());
        ringBuffer = new AudioRingBuffer(capacity);

        if (running) {
            stopCapture();
        }
        if (cfg.autoStart()) {
            startCapture();
        }
    }

    @Deactivate
    void deactivate() {
        stopCapture();
        clipExecutor.shutdownNow();
    }

    public boolean startCapture() {
        synchronized (lock) {
            if (running) {
                return true;
            }
            try {
                TargetDataLine newLine = openLine(format, preferredMixerName);
                newLine.open(format);
                newLine.start();
                line = newLine;
                running = true;
                captureThread = new Thread(this::captureLoop, "sl400-audio-capture");
                captureThread.setDaemon(true);
                captureThread.start();
                lastError = null;
                LOG.info("Audio capture started: {}", format);
                return true;
            } catch (Exception e) {
                lastError = e.getMessage();
                LOG.warn("Audio capture failed to start: {}", e.getMessage());
                running = false;
                return false;
            }
        }
    }

    public void stopCapture() {
        synchronized (lock) {
            running = false;
            if (line != null) {
                try {
                    line.stop();
                } catch (Exception ignored) {
                }
                try {
                    line.close();
                } catch (Exception ignored) {
                }
            }
            line = null;
            if (captureThread != null) {
                captureThread.interrupt();
            }
            captureThread = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getSampleRate() {
        return (int) format.getSampleRate();
    }

    public String status() {
        if (running) {
            return "SL400 audio: running (" + formatSummary() + ")";
        }
        if (lastError != null && !lastError.isBlank()) {
            return "SL400 audio: stopped (error: " + lastError + ")";
        }
        return "SL400 audio: stopped";
    }

    public CompletableFuture<Path> captureClipAsync(String incidentId) {
        if (!running) {
            return CompletableFuture.completedFuture(null);
        }
        long preBytes = msToBytes(preRollMs);
        long postBytes = msToBytes(postRollMs);
        long startOffset = ringBuffer.totalBytesWritten();
        byte[] pre = ringBuffer.readLatest((int) preBytes);

        return CompletableFuture.supplyAsync(() -> {
            try {
                waitForBytes(startOffset + postBytes, postRollMs + 2000);
                byte[] post = ringBuffer.readRange(startOffset, (int) postBytes);
                byte[] clip = new byte[pre.length + post.length];
                System.arraycopy(pre, 0, clip, 0, pre.length);
                System.arraycopy(post, 0, clip, pre.length, post.length);

                Files.createDirectories(clipsDir);
                String fileName = "sl400_clip_" + incidentId + ".wav";
                Path file = clipsDir.resolve(fileName);
                WavWriter.writePcmWav(file, clip, format);
                return file;
            } catch (Exception e) {
                LOG.warn("Failed to write clip: {}", e.getMessage());
                return null;
            }
        }, clipExecutor);
    }

    private void captureLoop() {
        byte[] buf = new byte[4096];
        while (running) {
            try {
                int read = line.read(buf, 0, buf.length);
                if (read > 0) {
                    ringBuffer.write(buf, read);
                }
            } catch (Exception e) {
                lastError = e.getMessage();
                LOG.warn("Audio capture error: {}", e.getMessage());
                sleepQuiet(500);
            }
        }
    }

    private String formatSummary() {
        return String.format(Locale.US, "%d Hz, %d-bit, %d ch",
                (int) format.getSampleRate(),
                format.getSampleSizeInBits(),
                format.getChannels());
    }

    private long msToBytes(long ms) {
        long bytesPerSecond = (long) format.getSampleRate()
                * format.getChannels()
                * (format.getSampleSizeInBits() / 8);
        return (bytesPerSecond * ms) / 1000L;
    }

    private void waitForBytes(long targetWritten, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (ringBuffer.totalBytesWritten() >= targetWritten) {
                return;
            }
            sleepQuiet(50);
        }
    }

    private TargetDataLine openLine(AudioFormat fmt, String preferredMixer) throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
        if (preferredMixer != null && !preferredMixer.isBlank()) {
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                if (!mixerInfo.getName().toLowerCase(Locale.US)
                        .contains(preferredMixer.toLowerCase(Locale.US))) {
                    continue;
                }
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                if (mixer.isLineSupported(info)) {
                    return (TargetDataLine) mixer.getLine(info);
                }
            }
        }
        return (TargetDataLine) AudioSystem.getLine(info);
    }

    private String resolveBaseDir(String raw) {
        String base = raw == null ? "${karaf.data}/sl400" : raw;
        return base.replace("${karaf.data}", System.getProperty("karaf.data", "data"));
    }

    private void sleepQuiet(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
