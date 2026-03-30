package de.drremote.trotecsl400.api;

public record AudioStatusSnapshot(
        boolean running,
        String statusText,
        int sampleRate
) {
}
