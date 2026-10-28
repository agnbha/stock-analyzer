package com.stockanalyzer.alert;

import com.stockanalyzer.model.Alert;
import com.stockanalyzer.model.AlertSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Desktop notification via osascript. Filtered by severity, so only what is
 * worth interrupting for leaves the terminal; {@code URGENT} also plays a sound.
 *
 * <p>osascript posts the banner as Script Editor, so macOS shows nothing unless
 * Script Editor is allowed to send notifications (System Settings > Notifications).
 * A failed or hung osascript is logged rather than thrown, because the alert
 * has already been recorded and printed by then.
 */
public final class MacNotificationSink implements AlertSink {

    private static final Logger log = LoggerFactory.getLogger(MacNotificationSink.class);

    private static final String URGENT_SOUND = "Glass";
    private static final long OSASCRIPT_TIMEOUT_SECONDS = 10;

    private final AlertSeverity minimumSeverity;
    private final Consumer<List<String>> launcher;

    public MacNotificationSink(AlertSeverity minimumSeverity) {
        this(minimumSeverity, MacNotificationSink::launch);
    }

    MacNotificationSink(AlertSeverity minimumSeverity, Consumer<List<String>> launcher) {
        this.minimumSeverity = minimumSeverity;
        this.launcher = launcher;
    }

    /** True where osascript exists; the other platforms have no desktop channel to offer. */
    public static boolean supportedHere() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    @Override
    public void publish(Alert alert) {
        if (alert.severity().ordinal() < minimumSeverity.ordinal()) {
            return;
        }
        launcher.accept(List.of("osascript", "-e", script(alert)));
    }

    static String script(Alert alert) {
        String title = alert.symbol() == null ? alert.title() : alert.symbol() + " - " + alert.title();
        StringBuilder script = new StringBuilder("display notification ")
                .append(quote(alert.message()))
                .append(" with title ").append(quote(title));
        if (alert.severity() == AlertSeverity.URGENT) {
            script.append(" sound name ").append(quote(URGENT_SOUND));
        }
        return script.toString();
    }

    static void launch(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.onExit()
                    .orTimeout(OSASCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .whenComplete((finished, failure) -> {
                        if (failure != null) {
                            process.destroyForcibly();
                            log.warn("Desktop notification gave up after {}s", OSASCRIPT_TIMEOUT_SECONDS);
                        } else if (finished.exitValue() != 0) {
                            log.warn("Desktop notification failed (osascript exit {}): {}",
                                    finished.exitValue(), output(finished));
                        }
                    });
        } catch (IOException e) {
            log.warn("Could not post desktop notification: {}", e.getMessage());
        }
    }

    private static String output(Process finished) {
        try {
            return new String(finished.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return "(no output: " + e.getMessage() + ")";
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
