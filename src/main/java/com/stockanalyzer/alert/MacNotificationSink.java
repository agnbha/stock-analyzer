package com.stockanalyzer.alert;

import com.stockanalyzer.model.Alert;
import com.stockanalyzer.model.AlertSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Desktop notification via osascript. Filtered by severity, so only what is
 * worth interrupting for leaves the terminal.
 */
public final class MacNotificationSink implements AlertSink {

    private static final Logger log = LoggerFactory.getLogger(MacNotificationSink.class);

    private final AlertSeverity minimumSeverity;

    public MacNotificationSink(AlertSeverity minimumSeverity) {
        this.minimumSeverity = minimumSeverity;
    }

    @Override
    public void publish(Alert alert) {
        if (alert.severity().ordinal() < minimumSeverity.ordinal()) {
            return;
        }
        String title = alert.symbol() == null ? alert.title() : alert.symbol() + " - " + alert.title();
        String script = "display notification " + quote(alert.message()) + " with title " + quote(title);
        try {
            new ProcessBuilder(List.of("osascript", "-e", script))
                    .redirectErrorStream(true)
                    .start();
        } catch (Exception e) {
            log.warn("Could not post desktop notification: {}", e.getMessage());
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
