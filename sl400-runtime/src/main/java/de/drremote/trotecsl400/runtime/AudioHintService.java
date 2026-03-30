package de.drremote.trotecsl400.runtime;

import de.drremote.trotecsl400.api.IncidentRepository;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component(immediate = true, service = AudioHintService.class)
public class AudioHintService {
    private static final Logger LOG = LoggerFactory.getLogger(AudioHintService.class);

    @Reference
    private IncidentRepository incidentRepository;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sl400-audio-hint");
        t.setDaemon(true);
        return t;
    });

    @Deactivate
    void deactivate() {
        executor.shutdownNow();
    }

    public CompletableFuture<String> analyzeAsync(String incidentId, Path clipPath, int sampleRate) {
        CompletableFuture<String> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                String hint = AudioHintAnalyzer.analyze(clipPath, sampleRate);
                incidentRepository.updateAudioHint(incidentId, hint);
                future.complete(hint);
            } catch (Exception e) {
                LOG.warn("Audio hint analysis failed: {}", e.getMessage());
                future.complete(null);
            }
        });
        return future;
    }
}
