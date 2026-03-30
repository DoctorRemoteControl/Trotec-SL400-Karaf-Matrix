package de.drremote.trotecsl400.api;

import java.util.List;

public interface IncidentRepository {
    void add(IncidentRecord record) throws Exception;

    List<IncidentRecord> getIncidentsBetween(long startMs, long endMs) throws Exception;

    List<IncidentRecord> getIncidentsSince(long durationMs, long nowMs) throws Exception;

    default List<IncidentRecord> getIncidentsSince(long durationMs) throws Exception {
        return getIncidentsSince(durationMs, System.currentTimeMillis());
    }

    IncidentRecord getIncidentById(String incidentId) throws Exception;

    IncidentRecord getLastClipIncident() throws Exception;

    List<IncidentRecord> getClipsSince(long durationMs, long nowMs) throws Exception;

    default List<IncidentRecord> getClipsSince(long durationMs) throws Exception {
        return getClipsSince(durationMs, System.currentTimeMillis());
    }

    boolean updateClip(String incidentId, String clipPath) throws Exception;

    boolean updateAudioHint(String incidentId, String audioHint) throws Exception;

    boolean markUploaded(String incidentId, String mxcUrl) throws Exception;

    int cleanupBefore(long cutoffMs) throws Exception;
}
