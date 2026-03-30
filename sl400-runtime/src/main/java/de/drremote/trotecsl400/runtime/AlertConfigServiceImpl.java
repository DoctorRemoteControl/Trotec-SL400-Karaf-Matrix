package de.drremote.trotecsl400.runtime;

import de.drremote.trotecsl400.api.AlertConfig;
import de.drremote.trotecsl400.api.AlertConfigService;
import de.drremote.trotecsl400.api.MetricMode;
import de.drremote.trotecsl400.api.SendMode;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import java.util.List;

@Component(service = AlertConfigService.class, configurationPid = "de.drremote.trotecsl400.alert")
@Designate(ocd = AlertConfigServiceImpl.Config.class)
public class AlertConfigServiceImpl implements AlertConfigService {

    @ObjectClassDefinition(name = "Trotec SL400 Alert Configuration")
    public @interface Config {
        @AttributeDefinition
        boolean enabled() default false;

        @AttributeDefinition
        double thresholdDb() default 70.0;

        @AttributeDefinition
        double hysteresisDb() default 2.0;

        @AttributeDefinition
        long minSendIntervalMs() default 60000L;

        @AttributeDefinition
        String sendMode() default "CROSSING_ONLY";

        @AttributeDefinition
        String metricMode() default "LAEQ_5_MIN";

        @AttributeDefinition
        String allowedSendersCsv() default "";

        @AttributeDefinition
        String commandRoomId() default "";

        @AttributeDefinition
        String targetRoomId() default "";

        @AttributeDefinition
        boolean alertHintFollowupEnabled() default true;

        @AttributeDefinition
        boolean dailyReportEnabled() default false;

        @AttributeDefinition
        int dailyReportHour() default 9;

        @AttributeDefinition
        int dailyReportMinute() default 0;

        @AttributeDefinition
        String dailyReportRoomId() default "";

        @AttributeDefinition
        boolean dailyReportJsonEnabled() default true;

        @AttributeDefinition
        boolean dailyReportGraphEnabled() default true;
    }

    private volatile AlertConfig config = AlertConfig.defaults();

    @Activate
    @Modified
    void activate(Config cfg) {
        SendMode sendMode = parseEnum(cfg.sendMode(), SendMode.CROSSING_ONLY);
        MetricMode metricMode = parseEnum(cfg.metricMode(), MetricMode.LAEQ_5_MIN);
        List<String> allowed = parseCsv(cfg.allowedSendersCsv());
        config = new AlertConfig(
                cfg.enabled(),
                cfg.thresholdDb(),
                cfg.hysteresisDb(),
                cfg.minSendIntervalMs(),
                sendMode,
                metricMode,
                allowed,
                cfg.commandRoomId(),
                cfg.targetRoomId(),
                cfg.alertHintFollowupEnabled(),
                cfg.dailyReportEnabled(),
                cfg.dailyReportHour(),
                cfg.dailyReportMinute(),
                cfg.dailyReportRoomId(),
                cfg.dailyReportJsonEnabled(),
                cfg.dailyReportGraphEnabled()
        );
    }

    @Override
    public AlertConfig getConfig() {
        return config;
    }

    private static <T extends Enum<T>> T parseEnum(String value, T fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            String normalized = value.trim().toUpperCase().replace('-', '_');
            return Enum.valueOf(fallback.getDeclaringClass(), normalized);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static List<String> parseCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(",")).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
