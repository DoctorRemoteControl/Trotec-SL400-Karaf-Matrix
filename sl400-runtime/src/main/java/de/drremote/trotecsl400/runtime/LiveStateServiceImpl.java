package de.drremote.trotecsl400.runtime;

import de.drremote.trotecsl400.api.AcousticMetrics;
import de.drremote.trotecsl400.api.LiveSnapshot;
import de.drremote.trotecsl400.api.LiveStateService;
import de.drremote.trotecsl400.api.Sl400Sample;
import org.osgi.service.component.annotations.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component(service = LiveStateService.class)
public class LiveStateServiceImpl implements LiveStateService {
    private final AtomicReference<LiveSnapshot> snapshot =
            new AtomicReference<>(LiveSnapshot.empty());

    @Override
    public void updateSample(Sl400Sample sample, AcousticMetrics metrics) {
        if (sample == null || metrics == null) {
            return;
        }
        snapshot.set(new LiveSnapshot(
                sample,
                metrics,
                sample.timestampMs(),
                null
        ));
    }

    @Override
    public void recordError(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        snapshot.updateAndGet(current -> new LiveSnapshot(
                current.lastSample(),
                current.lastMetrics(),
                current.lastSampleAtMs(),
                message
        ));
    }

    @Override
    public LiveSnapshot snapshot() {
        return snapshot.get();
    }
}
