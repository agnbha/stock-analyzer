package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.model.GainOpportunity;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.LiveOpportunityRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;

public final class SqliteLiveOpportunityRepository implements LiveOpportunityRepository {

    private final Database database;

    public SqliteLiveOpportunityRepository(Database database) {
        this.database = database;
    }

    @Override
    public void replace(long instrumentId, LocalDate sessionDate, String detectorVersion,
                        List<GainOpportunity> opportunities) {
        database.inTransaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM live_opportunity
                    WHERE instrument_id = ? AND session_date = ? AND detector_version = ?""")) {
                delete.setLong(1, instrumentId);
                delete.setString(2, sessionDate.toString());
                delete.setString(3, detectorVersion);
                delete.executeUpdate();
            }
            if (opportunities.isEmpty()) {
                return;
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO live_opportunity
                      (instrument_id, session_date, detector_version, rank, entry_ts, exit_ts,
                       entry_price, exit_price, gain_pct, duration_minutes, updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)""")) {
                long now = JdbcSupport.now();
                for (GainOpportunity o : opportunities) {
                    insert.setLong(1, instrumentId);
                    insert.setString(2, sessionDate.toString());
                    insert.setString(3, detectorVersion);
                    insert.setInt(4, o.rank());
                    insert.setLong(5, o.entryTs());
                    insert.setLong(6, o.exitTs());
                    insert.setDouble(7, o.entryPrice());
                    insert.setDouble(8, o.exitPrice());
                    insert.setDouble(9, o.gainPct());
                    insert.setInt(10, o.durationMinutes());
                    insert.setLong(11, now);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        });
    }

    @Override
    public int deleteForSession(LocalDate sessionDate) {
        return database.inTransaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM live_opportunity WHERE session_date = ?")) {
                delete.setString(1, sessionDate.toString());
                return delete.executeUpdate();
            }
        });
    }

    @Override
    public int countForSession(LocalDate sessionDate) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT COUNT(*) FROM live_opportunity WHERE session_date = ?")) {
                select.setString(1, sessionDate.toString());
                try (ResultSet rs = select.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }
}
