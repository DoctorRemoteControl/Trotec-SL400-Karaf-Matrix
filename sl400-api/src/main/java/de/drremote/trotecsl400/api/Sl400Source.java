package de.drremote.trotecsl400.api;

public interface Sl400Source {
    void setListener(Sl400SampleListener listener);

    void start();

    void stop();
}
