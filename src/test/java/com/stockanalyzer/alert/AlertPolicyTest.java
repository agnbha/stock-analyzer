package com.stockanalyzer.alert;

import com.stockanalyzer.model.Alert;
import com.stockanalyzer.model.AlertSeverity;
import com.stockanalyzer.store.AlertRepository;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.TestDatabase;
import com.stockanalyzer.store.jdbc.SqliteAlertRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertPolicyTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 27);
    private static final long NOON = 1756000000L;

    @TempDir
    Path directory;

    private Database database;
    private AlertRepository alertRepository;
    private List<Alert> delivered;
    private AlertEngine engine;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        alertRepository = new SqliteAlertRepository(database);
        delivered = new ArrayList<>();
        engine = new AlertEngine(new AlertPolicy(alertRepository, 15, 3, 5),
                delivered::add, alertRepository);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private static Alert alert(String symbol, String rule, long firedAt, String key) {
        return new Alert(DAY, firedAt, symbol, rule, AlertSeverity.NOTABLE, rule, "message", key);
    }

    @Test
    @DisplayName("the same alert never fires twice, so a restart is safe")
    void neverFiresTheSameAlertTwice() {
        assertTrue(engine.fire(alert("RELIANCE", "hotwindow.approaching", NOON, "key-1")));
        assertFalse(engine.fire(alert("RELIANCE", "hotwindow.approaching", NOON, "key-1")));

        assertEquals(1, delivered.size());
    }

    @Test
    @DisplayName("a second alert for the same symbol and rule waits out the cooldown")
    void enforcesCooldown() {
        engine.fire(alert("RELIANCE", "event.volume_spike", NOON, "key-1"));

        assertFalse(engine.fire(alert("RELIANCE", "event.volume_spike", NOON + 600, "key-2")),
                "ten minutes later is inside the fifteen minute cooldown");
        assertTrue(engine.fire(alert("RELIANCE", "event.volume_spike", NOON + 1200, "key-3")));
    }

    @Test
    @DisplayName("a symbol cannot flood the day on its own")
    void capsAlertsPerSymbol() {
        for (int i = 0; i < 3; i++) {
            engine.fire(alert("RELIANCE", "rule-" + i, NOON + i * 1000L, "key-" + i));
        }

        assertFalse(engine.fire(alert("RELIANCE", "rule-4", NOON + 9000, "key-4")));
        assertTrue(engine.fire(alert("TCS", "rule-4", NOON + 9000, "key-tcs")),
                "another symbol still has room");
    }

    @Test
    @DisplayName("the daily cap stops everything once reached")
    void capsAlertsPerDay() {
        engine.fire(alert(null, "session.open", NOON, "k1"));
        engine.fire(alert(null, "session.mid", NOON + 100, "k2"));
        engine.fire(alert(null, "session.closing", NOON + 200, "k3"));
        engine.fire(alert(null, "session.close", NOON + 300, "k4"));
        engine.fire(alert(null, "session.extra", NOON + 400, "k5"));

        assertFalse(engine.fire(alert("RELIANCE", "hotwindow.approaching", NOON + 500, "k6")));
        assertEquals(5, delivered.size());
    }
}
