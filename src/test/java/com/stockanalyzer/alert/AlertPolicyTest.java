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
        engine = new AlertEngine(new AlertPolicy(alertRepository), delivered::add, alertRepository);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private static Alert alert(String symbol, String rule, long firedAt, String key) {
        return new Alert(DAY, firedAt, symbol, rule, AlertSeverity.URGENT, rule, "message", key);
    }

    @Test
    @DisplayName("the same alert never fires twice, so a restart is safe")
    void neverFiresTheSameAlertTwice() {
        assertTrue(engine.fire(alert("RELIANCE", "sell.target.0.6", NOON, "key-1")));
        assertFalse(engine.fire(alert("RELIANCE", "sell.target.0.6", NOON + 150, "key-1")));

        // A fresh engine over the same database is a restarted daemon.
        AlertEngine restarted = new AlertEngine(new AlertPolicy(alertRepository), delivered::add, alertRepository);
        assertFalse(restarted.fire(alert("RELIANCE", "sell.target.0.6", NOON + 600, "key-1")));

        assertEquals(1, delivered.size());
    }

    @Test
    @DisplayName("different alerts are never held back: no cooldown, no daily cap")
    void nothingIsRationed() {
        for (int i = 0; i < 40; i++) {
            assertTrue(engine.fire(alert("RELIANCE", "sell.target.0.6", NOON + i, "key-" + i)), "alert " + i);
        }

        assertEquals(40, delivered.size());
        assertEquals(40, alertRepository.firedOn(DAY).size());
    }

    @Test
    @DisplayName("what fired is logged, so a later trade can be attributed to it")
    void firedAlertsAreLogged() {
        engine.fire(alert("TCS", "sell.target.1", NOON, "key-tcs"));

        List<Alert> logged = alertRepository.firedOn(DAY);
        assertEquals(1, logged.size());
        assertEquals("TCS", logged.getFirst().symbol());
        assertEquals("sell.target.1", logged.getFirst().rule());
    }
}
