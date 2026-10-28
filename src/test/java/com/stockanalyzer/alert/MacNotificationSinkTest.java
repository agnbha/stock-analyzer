package com.stockanalyzer.alert;

import com.stockanalyzer.model.Alert;
import com.stockanalyzer.model.AlertSeverity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacNotificationSinkTest {

    private static Alert alert(String symbol, AlertSeverity severity, String message) {
        return new Alert(LocalDate.of(2026, 10, 28), 1_761_650_000L, symbol, "rule", severity,
                "Volume spike", message, "key-" + severity);
    }

    private final List<List<String>> launched = new ArrayList<>();

    private MacNotificationSink sink(AlertSeverity minimum) {
        return new MacNotificationSink(minimum, launched::add);
    }

    @Test
    @DisplayName("an alert at or above the minimum severity is handed to osascript")
    void postsAtOrAboveTheMinimum() {
        MacNotificationSink sink = sink(AlertSeverity.NOTABLE);

        sink.publish(alert("INFY", AlertSeverity.NOTABLE, "Volume 3x normal"));
        sink.publish(alert("INFY", AlertSeverity.URGENT, "Entry window"));

        assertEquals(2, launched.size());
        assertEquals(List.of("osascript", "-e"), launched.get(0).subList(0, 2));
    }

    @Test
    @DisplayName("lifecycle chatter below the minimum stays in the console")
    void skipsBelowTheMinimum() {
        sink(AlertSeverity.NOTABLE).publish(alert(null, AlertSeverity.INFO, "Market opens in 2 minutes"));

        assertTrue(launched.isEmpty());
    }

    @Test
    @DisplayName("lowering the minimum to INFO lets lifecycle alerts through")
    void minimumIsConfigurable() {
        sink(AlertSeverity.INFO).publish(alert(null, AlertSeverity.INFO, "Market opens in 2 minutes"));

        assertEquals(1, launched.size());
    }

    @Test
    @DisplayName("the title leads with the symbol, and a market-wide alert has no prefix")
    void titleNamesTheSymbol() {
        assertEquals("display notification \"Volume 3x normal\" with title \"INFY - Volume spike\"",
                MacNotificationSink.script(alert("INFY", AlertSeverity.NOTABLE, "Volume 3x normal")));
        assertEquals("display notification \"Opens soon\" with title \"Volume spike\"",
                MacNotificationSink.script(alert(null, AlertSeverity.NOTABLE, "Opens soon")));
    }

    @Test
    @DisplayName("only URGENT plays a sound")
    void urgentPlaysASound() {
        assertTrue(MacNotificationSink.script(alert("INFY", AlertSeverity.URGENT, "Go"))
                .endsWith(" sound name \"Glass\""));
        assertFalse(MacNotificationSink.script(alert("INFY", AlertSeverity.NOTABLE, "Go"))
                .contains("sound name"));
    }

    @Test
    @DisplayName("quotes and backslashes in a message cannot break out of the AppleScript string")
    void escapesTheMessage() {
        String script = MacNotificationSink.script(
                alert("M&M", AlertSeverity.NOTABLE, "said \"buy\" \\ then \" & do shell script \"x"));

        assertTrue(script.contains("\"said \\\"buy\\\" \\\\ then \\\" & do shell script \\\"x\""), script);
    }

    @Test
    @DisplayName("a command that cannot start is logged, not thrown")
    void unstartableCommandDoesNotThrow() {
        assertDoesNotThrow(() -> MacNotificationSink.launch(List.of("no-such-osascript-for-tests", "-e", "x")));
    }

    @Test
    @DisplayName("a command that exits non-zero is logged, not thrown")
    void failingCommandDoesNotThrow() {
        assertDoesNotThrow(() -> MacNotificationSink.launch(List.of("false")));
    }
}
