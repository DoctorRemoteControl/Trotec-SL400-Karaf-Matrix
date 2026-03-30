package de.drremote.trotecsl400.runtime;

import de.drremote.trotecsl400.api.AlertConfig;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Dictionary;
import java.util.Hashtable;

@Component(service = AlertConfigAdminService.class)
public class AlertConfigAdminService {
    private static final Logger LOG = LoggerFactory.getLogger(AlertConfigAdminService.class);
    private static final String PID = "de.drremote.trotecsl400.alert";

    @Reference
    private ConfigurationAdmin configurationAdmin;

    public boolean updateOperatorConfig(AlertConfig cfg) {
        Dictionary<String, Object> props = new Hashtable<>();
        props.put("enabled", cfg.enabled());
        props.put("thresholdDb", cfg.thresholdDb());
        props.put("hysteresisDb", cfg.hysteresisDb());
        props.put("minSendIntervalMs", cfg.minSendIntervalMs());
        props.put("sendMode", cfg.sendMode().name());
        props.put("metricMode", cfg.metricMode().name());
        props.put("targetRoomId", cfg.targetRoomId());
        props.put("alertHintFollowupEnabled", cfg.alertHintFollowupEnabled());
        props.put("dailyReportEnabled", cfg.dailyReportEnabled());
        props.put("dailyReportHour", cfg.dailyReportHour());
        props.put("dailyReportMinute", cfg.dailyReportMinute());
        props.put("dailyReportRoomId", cfg.dailyReportRoomId());
        props.put("dailyReportJsonEnabled", cfg.dailyReportJsonEnabled());
        props.put("dailyReportGraphEnabled", cfg.dailyReportGraphEnabled());

        return updateProperties(props);
    }

    public boolean updateAllowedSendersCsv(String allowedSendersCsv) {
        Dictionary<String, Object> props = new Hashtable<>();
        props.put("allowedSendersCsv", allowedSendersCsv == null ? "" : allowedSendersCsv);
        return updateProperties(props);
    }

    public boolean updateCommandRoomId(String commandRoomId) {
        Dictionary<String, Object> props = new Hashtable<>();
        props.put("commandRoomId", commandRoomId == null ? "" : commandRoomId);
        return updateProperties(props);
    }

    private boolean updateProperties(Dictionary<String, Object> updates) {
        try {
            Configuration cfg = configurationAdmin.getConfiguration(PID, null);
            Dictionary<String, Object> current = cfg.getProperties();
            Dictionary<String, Object> merged = new Hashtable<>();

            if (current != null) {
                for (java.util.Enumeration<String> e = current.keys(); e.hasMoreElements(); ) {
                    String key = e.nextElement();
                    merged.put(key, current.get(key));
                }
            }
            for (java.util.Enumeration<String> e = updates.keys(); e.hasMoreElements(); ) {
                String key = e.nextElement();
                merged.put(key, updates.get(key));
            }

            cfg.update(merged);
            return true;
        } catch (IOException e) {
            LOG.error("Failed to update AlertConfig via ConfigAdmin", e);
            return false;
        }
    }
}
