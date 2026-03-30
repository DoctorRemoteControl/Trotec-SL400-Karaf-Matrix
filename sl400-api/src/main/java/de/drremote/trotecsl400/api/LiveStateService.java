package de.drremote.trotecsl400.api;

public interface LiveStateService {
    void updateSample(Sl400Sample sample, AcousticMetrics metrics);

    void recordError(String message);

    LiveSnapshot snapshot();
}
