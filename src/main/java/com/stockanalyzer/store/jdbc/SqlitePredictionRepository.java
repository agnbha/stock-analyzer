package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.model.SignalPrediction;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.PredictionRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class SqlitePredictionRepository implements PredictionRepository {

    private final Database database;

    public SqlitePredictionRepository(Database database) {
        this.database = database;
    }

    @Override
    public long findOrCreateModelVersion(String name) {
        return database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT OR IGNORE INTO model_version (name, trained_at, active) VALUES (?,?,1)")) {
                insert.setString(1, name);
                insert.setLong(2, JdbcSupport.now());
                insert.executeUpdate();
            }
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id FROM model_version WHERE name = ?")) {
                select.setString(1, name);
                try (ResultSet rs = select.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    @Override
    public void saveAll(long modelVersionId, long instrumentId, List<SignalPrediction> predictions) {
        if (predictions.isEmpty()) {
            return;
        }
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT OR REPLACE INTO prediction
                      (model_version_id, instrument_id, ts_epoch, session_date, signal, probability, horizon_minutes)
                    VALUES (?,?,?,?,?,?,?)""")) {
                for (SignalPrediction p : predictions) {
                    insert.setLong(1, modelVersionId);
                    insert.setLong(2, instrumentId);
                    insert.setLong(3, p.tsEpoch());
                    insert.setString(4, p.sessionDate().toString());
                    insert.setString(5, p.signal().name());
                    insert.setDouble(6, p.probability());
                    insert.setInt(7, p.horizonMinutes());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        });
    }

    @Override
    public List<StoredPrediction> unscored(LocalDate sessionDate) {
        return database.read(connection -> {
            List<StoredPrediction> rows = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT p.id, p.instrument_id, i.symbol, p.ts_epoch, p.session_date, p.signal,
                           p.probability, p.horizon_minutes
                    FROM prediction p JOIN instrument i ON i.id = p.instrument_id
                    WHERE p.session_date = ? AND p.realized_return_pct IS NULL""")) {
                select.setString(1, sessionDate.toString());
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        SignalPrediction prediction = new SignalPrediction(
                                rs.getString("symbol"),
                                LocalDate.parse(rs.getString("session_date")),
                                rs.getLong("ts_epoch"),
                                SignalPrediction.Signal.valueOf(rs.getString("signal")),
                                rs.getDouble("probability"),
                                rs.getInt("horizon_minutes"),
                                null);
                        rows.add(new StoredPrediction(rs.getLong("id"), rs.getLong("instrument_id"),
                                rs.getString("symbol"), prediction));
                    }
                }
            }
            return rows;
        });
    }

    @Override
    public void setRealizedReturn(long predictionId, double realizedReturnPct) {
        database.inTransaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE prediction SET realized_return_pct = ? WHERE id = ?")) {
                update.setDouble(1, realizedReturnPct);
                update.setLong(2, predictionId);
                update.executeUpdate();
            }
        });
    }
}
