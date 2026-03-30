package de.drremote.trotecsl400.api;

@FunctionalInterface
public interface Sl400SampleListener {
    void onSample(Sl400Sample sample);
}
