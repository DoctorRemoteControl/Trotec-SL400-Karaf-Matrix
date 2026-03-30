package de.drremote.trotecsl400.api;

public interface MatrixPublisher {
    void sendAlert(MatrixConfig config, Sl400Sample sample, AcousticMetrics metrics,
                   AlertConfig alertConfig, String audioHint) throws Exception;

    void sendStatus(MatrixConfig config, Sl400Sample sample, AcousticMetrics metrics,
                    boolean serialOnline, boolean audioRunning) throws Exception;

    void sendText(MatrixConfig config, String roomId, String body) throws Exception;

    void sendFile(MatrixConfig config, String roomId, java.nio.file.Path file,
                  String body, String mimeType) throws Exception;

    void sendImage(MatrixConfig config, String roomId, java.nio.file.Path file,
                   String body, String mimeType) throws Exception;

    String sendAudio(MatrixConfig config, String roomId, java.nio.file.Path file,
                     String body, String mimeType) throws Exception;

    void sendAudioByMxcUrl(MatrixConfig config, String roomId, String mxcUrl,
                           String body, String mimeType, String fileName, Long size) throws Exception;

    String sendClip(MatrixConfig config, String roomId, String incidentId, long timestampMs,
                    java.nio.file.Path file, String body, String mimeType) throws Exception;

    void sendClipByMxcUrl(MatrixConfig config, String roomId, String incidentId, long timestampMs,
                          String mxcUrl, String body, String mimeType, String fileName, Long size) throws Exception;
}
