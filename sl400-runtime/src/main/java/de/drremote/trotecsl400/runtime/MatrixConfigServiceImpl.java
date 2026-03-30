package de.drremote.trotecsl400.runtime;

import de.drremote.trotecsl400.api.MatrixConfig;
import de.drremote.trotecsl400.api.MatrixConfigService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@Component(service = MatrixConfigService.class, configurationPid = "de.drremote.trotecsl400.matrix")
@Designate(ocd = MatrixConfigServiceImpl.Config.class)
public class MatrixConfigServiceImpl implements MatrixConfigService {

    @ObjectClassDefinition(name = "Trotec SL400 Matrix Configuration")
    public @interface Config {
        @AttributeDefinition
        String homeserverBaseUrl() default "";

        @AttributeDefinition
        String accessToken() default "";

        @AttributeDefinition
        String roomId() default "";

        @AttributeDefinition
        String deviceId() default "";

        @AttributeDefinition
        boolean enabled() default false;
    }

    private volatile MatrixConfig config = MatrixConfig.defaults();

    @Activate
    @Modified
    void activate(Config cfg) {
        String deviceId = cfg.deviceId();
        if (deviceId == null || deviceId.isBlank()) {
            deviceId = System.getProperty("sl400.deviceId", "");
        }
        if (deviceId == null || deviceId.isBlank()) {
            deviceId = resolveHostname();
        }
        config = new MatrixConfig(
                cfg.homeserverBaseUrl(),
                cfg.accessToken(),
                cfg.roomId(),
                deviceId == null ? "" : deviceId.trim(),
                cfg.enabled()
        );
    }

    @Override
    public MatrixConfig getConfig() {
        return config;
    }

    private String resolveHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "";
        }
    }
}
