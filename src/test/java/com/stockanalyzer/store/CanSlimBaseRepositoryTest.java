package com.stockanalyzer.store;

import com.stockanalyzer.canslim.BaseMark;
import com.stockanalyzer.store.jdbc.SqliteCanSlimRepository;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The bases the CAN SLIM chart draws as boxes: stored a stock at a time, numbered from the oldest, read back by symbol. */
class CanSlimBaseRepositoryTest {

    @TempDir
    Path directory;

    private Database database;
    private SqliteCanSlimRepository repository;
    private long alpha;
    private long beta;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        repository = new SqliteCanSlimRepository(database);
        SqliteInstrumentRepository instruments = new SqliteInstrumentRepository(database);
        alpha = instruments.findOrCreate("ALPHA", "NSE", "CASH");
        beta = instruments.findOrCreate("BETA", "NSE", "CASH");
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private static BaseMark mark(BaseMark.Kind kind, String from, String to, double pivot, double low) {
        return new BaseMark(kind, LocalDate.parse(from), LocalDate.parse(to), pivot, low, 40);
    }

    @Test
    @DisplayName("a stock's bases are stored with their box, their depth and their order, and read back by symbol")
    void storedAndRead() {
        repository.saveBases(Map.of(alpha, List.of(
                mark(BaseMark.Kind.BREAKOUT, "2026-01-05", "2026-03-10", 200.0, 160.0),
                mark(BaseMark.Kind.CURRENT, "2026-08-14", "2026-11-25", 300.0, 270.0))));

        assertEquals(List.of("ALPHA|2026-01-05|2026-03-10|BREAKOUT|200.0|160.0|20.0|0",
                        "ALPHA|2026-08-14|2026-11-25|CURRENT|300.0|270.0|10.0|1"),
                rows("SELECT symbol, pivot_date, end_date, kind, pivot, low, depth_pct, seq FROM v_canslim_base ORDER BY seq"));
    }

    @Test
    @DisplayName("saving a stock's bases again replaces its rows, and leaves the other stocks' alone")
    void replacedPerStock() {
        repository.saveBases(Map.of(
                alpha, List.of(mark(BaseMark.Kind.BREAKOUT, "2026-01-05", "2026-03-10", 200.0, 160.0)),
                beta, List.of(mark(BaseMark.Kind.CURRENT, "2026-09-01", "2026-11-25", 50.0, 45.0))));

        repository.saveBases(Map.of(alpha, List.of(mark(BaseMark.Kind.CURRENT, "2026-10-01", "2026-11-25", 210.0, 190.0))));

        assertEquals(List.of("ALPHA|2026-10-01|CURRENT", "BETA|2026-09-01|CURRENT"),
                rows("SELECT symbol, pivot_date, kind FROM v_canslim_base ORDER BY symbol"));
    }

    @Test
    @DisplayName("a stock given no bases has none left, which is how one that left its base loses its box")
    void emptyClears() {
        repository.saveBases(Map.of(alpha, List.of(mark(BaseMark.Kind.CURRENT, "2026-10-01", "2026-11-25", 210.0, 190.0))));

        repository.saveBases(Map.of(alpha, List.of()));

        assertEquals(List.of(), rows("SELECT symbol FROM v_canslim_base"));
        repository.saveBases(Map.of());
    }

    private List<String> rows(String sql) {
        return database.read(connection -> {
            List<String> rows = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                int columns = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    List<String> cells = new ArrayList<>();
                    for (int c = 1; c <= columns; c++) {
                        cells.add(rs.getString(c));
                    }
                    rows.add(String.join("|", cells));
                }
            }
            return rows;
        });
    }
}
