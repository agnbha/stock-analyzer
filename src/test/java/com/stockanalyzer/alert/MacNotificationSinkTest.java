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

        List<List<String>> banners = launched.stream().filter(c -> c.getFirst().equals("osascript")).toList();
        assertEquals(2, banners.size());
        assertEquals(List.of("osascript", "-e"), banners.get(0).subList(0, 2));
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
    @DisplayName("an URGENT alert plays the Glass chime itself, before the banner, and nothing less does")
    void urgentPlaysASoundItself() {
        MacNotificationSink sink = sink(AlertSeverity.INFO);

        sink.publish(alert("INFY", AlertSeverity.NOTABLE, "Go"));
        assertEquals(1, launched.size(), "a notable alert is a banner only");
        assertEquals("osascript", launched.getFirst().getFirst());

        launched.clear();
        sink.publish(alert("INFY", AlertSeverity.URGENT, "Sell now"));
        assertEquals(2, launched.size());
        assertEquals(List.of("afplay", "/System/Library/Sounds/Glass.aiff"), launched.get(0), "the sound first");
        assertEquals("osascript", launched.get(1).getFirst(), "then the banner");
    }

    @Test
    @DisplayName("the banner carries no sound of its own: it would double the chime, or be silent when muted for Script Editor")
    void theBannerIsSilent() {
        assertFalse(MacNotificationSink.script(alert("INFY", AlertSeverity.URGENT, "Go")).contains("sound name"));
        assertFalse(MacNotificationSink.script(alert("INFY", AlertSeverity.NOTABLE, "Go")).contains("sound name"));
    }

    @Test
    @DisplayName("a sound that cannot be started does not cost the banner")
    void aBrokenSoundKeepsTheBanner() {
        List<List<String>> posted = new ArrayList<>();
        MacNotificationSink sink = new MacNotificationSink(AlertSeverity.NOTABLE, command -> {
            if (command.getFirst().equals("afplay")) {
                throw new IllegalStateException("no audio device");
            }
            posted.add(command);
        });

        sink.publish(alert("INFY", AlertSeverity.URGENT, "Sell now"));

        assertEquals(1, posted.size());
        assertEquals("osascript", posted.getFirst().getFirst());
    }

    @Test
    @DisplayName("the chime is a file macOS ships, so it is there on every Mac")
    void theChimeExistsOnMac() {
        org.junit.jupiter.api.Assumptions.assumeTrue(MacNotificationSink.supportedHere());

        assertTrue(java.nio.file.Files.isRegularFile(java.nio.file.Path.of(MacNotificationSink.URGENT_SOUND_FILE)));
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
