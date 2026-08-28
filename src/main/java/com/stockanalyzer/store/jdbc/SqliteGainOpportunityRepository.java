package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.model.GainOpportunity;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.GainOpportunityRepository;
import com.stockanalyzer.store.OpportunityRow;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class SqliteGainOpportunityRepository implements GainOpportunityRepository {

    private static final String SELECT_JOINED = """
            SELECT o.id, i.symbol, t.session_date, o.rank, o.entry_ts, o.exit_ts,
                   o.entry_price, o.exit_price, o.gain_pct, o.duration_minutes
            FROM gain_opportunity o
            JOIN trading_day t ON t.id = o.trading_day_id
            JOIN instrument i ON i.id = t.instrument_id
            WHERE o.detector_version = ? AND t.session_date BETWEEN ? AND ?""";

    private final Database database;

    public SqliteGainOpportunityRepository(Database database) {
        this.database = database;
    }

    @Override
    public void replace(long tradingDayId, String detectorVersion, List<GainOpportunity> opportunities) {
        database.inTransaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM gain_opportunity WHERE trading_day_id = ? AND detector_version = ?")) {
                delete.setLong(1, tradingDayId);
                delete.setString(2, detectorVersion);
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO gain_opportunity
                      (trading_day_id, detector_version, rank, entry_ts, exit_ts,
                       entry_price, exit_price, gain_pct, duration_minutes)
                    VALUES (?,?,?,?,?,?,?,?,?)""")) {
                for (GainOpportunity o : opportunities) {
                    insert.setLong(1, tradingDayId);
                    insert.setString(2, detectorVersion);
                    insert.setInt(3, o.rank());
                    insert.setLong(4, o.entryTs());
                    insert.setLong(5, o.exitTs());
                    insert.setDouble(6, o.entryPrice());
                    insert.setDouble(7, o.exitPrice());
                    insert.setDouble(8, o.gainPct());
                    insert.setInt(9, o.durationMinutes());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        });
    }

    @Override
    public List<GainOpportunity> findByTradingDay(long tradingDayId, String detectorVersion) {
        return database.read(connection -> {
            List<GainOpportunity> rows = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT rank, entry_ts, exit_ts, entry_price, exit_price, gain_pct, duration_minutes
                    FROM gain_opportunity WHERE trading_day_id = ? AND detector_version = ? ORDER BY rank""")) {
                select.setLong(1, tradingDayId);
                select.setString(2, detectorVersion);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        rows.add(mapOpportunity(rs));
                    }
                }
            }
            return rows;
        });
    }

    @Override
    public List<OpportunityRow> findRange(LocalDate from, LocalDate to, String detectorVersion, String symbolOrNull) {
        String sql = SELECT_JOINED
                + (symbolOrNull == null ? "" : " AND i.symbol = ?")
                + " ORDER BY t.session_date DESC, i.symbol, o.rank";
        return database.read(connection -> {
            List<OpportunityRow> rows = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(sql)) {
                select.setString(1, detectorVersion);
                select.setString(2, from.toString());
                select.setString(3, to.toString());
                if (symbolOrNull != null) {
                    select.setString(4, symbolOrNull);
                }
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        rows.add(mapRow(rs));
                    }
                }
            }
            return rows;
        });
    }

    @Override
    public List<OpportunityRow> findForHotWindows(LocalDate from, LocalDate to, String detectorVersion) {
        return findRange(from, to, detectorVersion, null);
    }

    private static OpportunityRow mapRow(ResultSet rs) throws SQLException {
        return new OpportunityRow(
                rs.getLong("id"),
                rs.getString("symbol"),
                LocalDate.parse(rs.getString("session_date")),
                mapOpportunity(rs));
    }

    private static GainOpportunity mapOpportunity(ResultSet rs) throws SQLException {
        return new GainOpportunity(
                rs.getInt("rank"), rs.getLong("entry_ts"), rs.getLong("exit_ts"),
                rs.getDouble("entry_price"), rs.getDouble("exit_price"),
                rs.getDouble("gain_pct"), rs.getInt("duration_minutes"));
    }
}
