package de.drremote.trotecsl400.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.drremote.trotecsl400.api.IncidentRecord;
import de.drremote.trotecsl400.api.IncidentRepository;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Component(service = IncidentRepository.class, configurationPid = "de.drremote.trotecsl400.storage")
@Designate(ocd = JsonIncidentRepository.Config.class)
public class JsonIncidentRepository implements IncidentRepository {
    @ObjectClassDefinition(name = "Trotec SL400 JSON Storage")
    public @interface Config {
        @AttributeDefinition
        String baseDir() default "${karaf.data}/sl400";

        @AttributeDefinition
        String fileName() default "incidents.jsonl";
    }

    private final Object lock = new Object();
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private volatile Path baseDir;
    private volatile Path file;

    @Activate
    @Modified
    void activate(Config cfg) {
        String raw = cfg.baseDir();
        String resolved = raw.replace(
                "${karaf.data}",
                System.getProperty("karaf.data", "data")
        );
        baseDir = Path.of(resolved);
        file = baseDir.resolve(cfg.fileName());
    }

    @Override
    public void add(IncidentRecord record) {
        synchronized (lock) {
            ensureDir();
            try (BufferedWriter writer = Files.newBufferedWriter(
                    file,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            )) {
                writer.write(mapper.writeValueAsString(record));
                writer.newLine();
            } catch (Exception e) {
                throw new RuntimeException("Failed to append incident record", e);
            }
        }
    }

    @Override
    public List<IncidentRecord> getIncidentsBetween(long startMs, long endMs) {
        synchronized (lock) {
            List<IncidentRecord> all = readAll();
            List<IncidentRecord> out = new ArrayList<>();
            for (IncidentRecord r : all) {
                long ts = r.timestampMs();
                if (ts >= startMs && ts < endMs) {
                    out.add(r);
                }
            }
            return out;
        }
    }

    @Override
    public List<IncidentRecord> getIncidentsSince(long durationMs, long nowMs) {
        long cutoff = nowMs - durationMs;
        synchronized (lock) {
            List<IncidentRecord> all = readAll();
            List<IncidentRecord> out = new ArrayList<>();
            for (IncidentRecord r : all) {
                if (r.timestampMs() >= cutoff) {
                    out.add(r);
                }
            }
            return out;
        }
    }

    @Override
    public IncidentRecord getIncidentById(String incidentId) {
        if (incidentId == null || incidentId.isBlank()) {
            return null;
        }
        synchronized (lock) {
            for (IncidentRecord r : readAll()) {
                if (incidentId.equals(r.incidentId())) {
                    return r;
                }
            }
            return null;
        }
    }

    @Override
    public IncidentRecord getLastClipIncident() {
        synchronized (lock) {
            return readAll().stream()
                    .filter(r -> r.clipPath() != null && !r.clipPath().isBlank())
                    .max(Comparator.comparingLong(IncidentRecord::timestampMs))
                    .orElse(null);
        }
    }

    @Override
    public List<IncidentRecord> getClipsSince(long durationMs, long nowMs) {
        long cutoff = nowMs - durationMs;
        synchronized (lock) {
            List<IncidentRecord> out = new ArrayList<>();
            for (IncidentRecord r : readAll()) {
                if (r.timestampMs() >= cutoff
                        && r.clipPath() != null
                        && !r.clipPath().isBlank()) {
                    out.add(r);
                }
            }
            return out;
        }
    }

    @Override
    public boolean updateClip(String incidentId, String clipPath) {
        if (incidentId == null || incidentId.isBlank()) {
            return false;
        }
        synchronized (lock) {
            List<IncidentRecord> all = readAll();
            boolean updated = false;
            List<IncidentRecord> out = new ArrayList<>(all.size());
            for (IncidentRecord r : all) {
                if (!incidentId.equals(r.incidentId())) {
                    out.add(r);
                    continue;
                }
                String newClipPath = (clipPath == null || clipPath.isBlank()) ? null : clipPath;
                boolean clipPathChanged = (r.clipPath() == null && newClipPath != null)
                        || (r.clipPath() != null && !r.clipPath().equals(newClipPath));
                boolean clipUploaded = clipPathChanged ? false : r.clipUploaded();
                String mxcUrl = clipPathChanged ? null : r.mxcUrl();
                out.add(new IncidentRecord(
                        r.incidentId(),
                        r.timestampMs(),
                        r.roomId(),
                        r.metricMode(),
                        r.metricValue(),
                        r.thresholdDb(),
                        r.laEq1Min(),
                        r.laEq5Min(),
                        r.laEq15Min(),
                        r.maxDb1Min(),
                        r.timeAboveThresholdMs1Min(),
                        newClipPath,
                        clipUploaded,
                        mxcUrl,
                        r.audioHint()
                ));
                updated = true;
            }
            if (updated) {
                writeAll(out);
            }
            return updated;
        }
    }

    @Override
    public boolean updateAudioHint(String incidentId, String audioHint) {
        if (incidentId == null || incidentId.isBlank()) {
            return false;
        }
        synchronized (lock) {
            List<IncidentRecord> all = readAll();
            boolean updated = false;
            List<IncidentRecord> out = new ArrayList<>(all.size());
            for (IncidentRecord r : all) {
                if (!incidentId.equals(r.incidentId())) {
                    out.add(r);
                    continue;
                }
                out.add(new IncidentRecord(
                        r.incidentId(),
                        r.timestampMs(),
                        r.roomId(),
                        r.metricMode(),
                        r.metricValue(),
                        r.thresholdDb(),
                        r.laEq1Min(),
                        r.laEq5Min(),
                        r.laEq15Min(),
                        r.maxDb1Min(),
                        r.timeAboveThresholdMs1Min(),
                        r.clipPath(),
                        r.clipUploaded(),
                        r.mxcUrl(),
                        audioHint
                ));
                updated = true;
            }
            if (updated) {
                writeAll(out);
            }
            return updated;
        }
    }

    @Override
    public boolean markUploaded(String incidentId, String mxcUrl) {
        if (incidentId == null || incidentId.isBlank()) {
            return false;
        }
        synchronized (lock) {
            List<IncidentRecord> all = readAll();
            boolean updated = false;
            List<IncidentRecord> out = new ArrayList<>(all.size());
            for (IncidentRecord r : all) {
                if (!incidentId.equals(r.incidentId())) {
                    out.add(r);
                    continue;
                }
                out.add(new IncidentRecord(
                        r.incidentId(),
                        r.timestampMs(),
                        r.roomId(),
                        r.metricMode(),
                        r.metricValue(),
                        r.thresholdDb(),
                        r.laEq1Min(),
                        r.laEq5Min(),
                        r.laEq15Min(),
                        r.maxDb1Min(),
                        r.timeAboveThresholdMs1Min(),
                        r.clipPath(),
                        true,
                        (mxcUrl == null || mxcUrl.isBlank()) ? null : mxcUrl,
                        r.audioHint()
                ));
                updated = true;
            }
            if (updated) {
                writeAll(out);
            }
            return updated;
        }
    }

    @Override
    public int cleanupBefore(long cutoffMs) {
        synchronized (lock) {
            List<IncidentRecord> all = readAll();
            List<IncidentRecord> keep = new ArrayList<>();
            int removed = 0;
            for (IncidentRecord r : all) {
                if (r.timestampMs() >= cutoffMs) {
                    keep.add(r);
                } else {
                    removed++;
                    deleteClipFileQuiet(r.clipPath());
                }
            }
            if (removed > 0) {
                writeAll(keep);
            }
            return removed;
        }
    }

    private void ensureDir() {
        try {
            Files.createDirectories(baseDir);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create base dir: " + baseDir, e);
        }
    }

    private List<IncidentRecord> readAll() {
        if (file == null || !Files.exists(file)) {
            return List.of();
        }
        List<IncidentRecord> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(file)) {
            lines.map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(line -> {
                        try {
                            out.add(mapper.readValue(line, IncidentRecord.class));
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to parse incident JSONL line", e);
                        }
                    });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to read incident records", e);
        }
        return out;
    }

    private void writeAll(List<IncidentRecord> records) {
        ensureDir();
        Path temp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(
                temp,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        )) {
            for (IncidentRecord record : records) {
                writer.write(mapper.writeValueAsString(record));
                writer.newLine();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to write incident records", e);
        }
        try {
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new RuntimeException("Failed to replace incident records file", e);
        }
    }

    private void deleteClipFileQuiet(String clipPath) {
        if (clipPath == null || clipPath.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(clipPath));
        } catch (Exception ignored) {
        }
    }
}
