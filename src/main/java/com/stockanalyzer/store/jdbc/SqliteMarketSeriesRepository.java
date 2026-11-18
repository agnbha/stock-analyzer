package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.markets.Observation;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.MarketSeriesRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public final class SqliteMarketSeriesRepository implements MarketSeriesRepository {

    private final Database database;

    public SqliteMarketSeriesRepository(Database database) {
        this.database = database;
    }

    @Override
    public void upsert(String series, List<Observation> observations, long fetchedAtEpoch) {
        if (observations.isEmpty()) {
            return;
        }
        database.inTransaction(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT OR REPLACE INTO market_series (series, observed_on, value, fetched_at)
                    VALUES (?,?,?,?)""")) {
                for (Observation observation : observations) {
                    upsert.setString(1, series);
                    upsert.setString(2, observation.date().toString());
                    upsert.setDouble(3, observation.value());
                    upsert.setLong(4, fetchedAtEpoch);
                    upsert.addBatch();
                }
                upsert.executeBatch();
            }
        });
    }

    @Override
    public Optional<LocalDate> latest(String series) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT MAX(observed_on) FROM market_series WHERE series = ?")) {
                select.setString(1, series);
                try (ResultSet rs = select.executeQuery()) {
                    String latest = rs.next() ? rs.getString(1) : null;
                    return latest == null ? Optional.empty() : Optional.of(LocalDate.parse(latest));
                }
            }
        });
    }
}
