package de.drremote.trotecsl400.api;

public record MatrixConfig(
        String homeserverBaseUrl,
        String accessToken,
        String roomId,
        String deviceId,
        boolean enabled
) {
    public static MatrixConfig defaults() {
        return new MatrixConfig("", "", "", "", false);
    }
}
