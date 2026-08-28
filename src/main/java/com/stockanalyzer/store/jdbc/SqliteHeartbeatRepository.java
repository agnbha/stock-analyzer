package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.HeartbeatRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.Optional;

public final class SqliteHeartbeatRepository implements HeartbeatRepository {

    private final Database database;

    public SqliteHeartbeatRepository(Database database) {
        this.database = database;
    }

    @Override
    public void beat(LocalDate sessionDate, int symbolsTracked, boolean degraded, String note) {
        database.inTransaction(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT OR REPLACE INTO monitor_heartbeat
                      (id, session_date, last_tick_epoch, symbols_tracked, degraded, note)
                    VALUES (1, ?, ?, ?, ?, ?)""")) {
                upsert.setString(1, sessionDate == null ? null : sessionDate.toString());
                upsert.setLong(2, JdbcSupport.now());
                upsert.setInt(3, symbolsTracked);
                upsert.setInt(4, degraded ? 1 : 0);
                upsert.setString(5, note);
                upsert.executeUpdate();
            }
        });
    }

    @Override
    public Optional<Heartbeat> latest() {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT session_date, last_tick_epoch, symbols_tracked, degraded, note "
                            + "FROM monitor_heartbeat WHERE id = 1");
                 ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    return Optional.<Heartbeat>empty();
                }
                String date = rs.getString("session_date");
                return Optional.of(new Heartbeat(date == null ? null : LocalDate.parse(date),
                        rs.getLong("last_tick_epoch"), rs.getInt("symbols_tracked"),
                        rs.getInt("degraded") == 1, rs.getString("note")));
            }
        });
    }
}
