package de.drremote.trotecsl400.runtime;

import de.drremote.trotecsl400.api.AudioStatusService;
import de.drremote.trotecsl400.api.AudioStatusSnapshot;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

@Component(service = AudioStatusService.class)
public class AudioStatusServiceImpl implements AudioStatusService {
    @Reference
    private AudioCaptureService audioCaptureService;

    @Override
    public AudioStatusSnapshot getStatus() {
        return new AudioStatusSnapshot(
                audioCaptureService.isRunning(),
                audioCaptureService.status(),
                audioCaptureService.getSampleRate()
        );
    }
}
