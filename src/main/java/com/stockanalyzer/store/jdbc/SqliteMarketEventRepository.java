package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.model.EventType;
import com.stockanalyzer.model.MarketEvent;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.MarketEventRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class SqliteMarketEventRepository implements MarketEventRepository {

    private final Database database;

    public SqliteMarketEventRepository(Database database) {
        this.database = database;
    }

    @Override
    public void saveAll(long instrumentId, List<MarketEvent> events, String detectorVersion) {
        if (events.isEmpty()) {
            return;
        }
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT OR IGNORE INTO event
                      (instrument_id, ts_epoch, session_date, event_type, strength, detector_version)
                    VALUES (?,?,?,?,?,?)""")) {
                for (MarketEvent e : events) {
                    insert.setLong(1, instrumentId);
                    insert.setLong(2, e.tsEpoch());
                    insert.setString(3, e.sessionDate().toString());
                    insert.setString(4, e.type().name());
                    insert.setDouble(5, e.strength());
                    insert.setString(6, detectorVersion);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        });
    }

    @Override
    public List<MarketEvent> find(long instrumentId, LocalDate sessionDate) {
        return database.read(connection -> {
            List<MarketEvent> events = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT i.symbol AS symbol, e.* FROM event e JOIN instrument i ON i.id = e.instrument_id
                    WHERE e.instrument_id = ? AND e.session_date = ? ORDER BY e.ts_epoch""")) {
                select.setLong(1, instrumentId);
                select.setString(2, sessionDate.toString());
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        events.add(map(rs));
                    }
                }
            }
            return events;
        });
    }

    @Override
    public List<MarketEvent> findAllForSession(LocalDate sessionDate) {
        return database.read(connection -> {
            List<MarketEvent> events = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT i.symbol AS symbol, e.* FROM event e JOIN instrument i ON i.id = e.instrument_id
                    WHERE e.session_date = ? ORDER BY e.ts_epoch""")) {
                select.setString(1, sessionDate.toString());
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        events.add(map(rs));
                    }
                }
            }
            return events;
        });
    }

    private static MarketEvent map(ResultSet rs) throws SQLException {
        return new MarketEvent(rs.getString("symbol"), LocalDate.parse(rs.getString("session_date")),
                rs.getLong("ts_epoch"), EventType.valueOf(rs.getString("event_type")), rs.getDouble("strength"));
    }
}
