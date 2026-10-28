package com.stockanalyzer.store.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.stockanalyzer.canslim.CompanyFundamentals;
import com.stockanalyzer.store.DataAccessException;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.FundamentalsCacheRepository;
import com.stockanalyzer.util.JsonMapper;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;

public final class SqliteFundamentalsCacheRepository implements FundamentalsCacheRepository {

    private static final TypeReference<Map<String, Double>> RATIOS = new TypeReference<>() {
    };

    private final Database database;

    public SqliteFundamentalsCacheRepository(Database database) {
        this.database = database;
    }

    @Override
    public Optional<Cached> find(long instrumentId) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT fetched_at, ratios_json, results_quarter, holding_quarter
                    FROM screener_fundamentals WHERE instrument_id = ?""")) {
                select.setLong(1, instrumentId);
                try (ResultSet rs = select.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<Cached>empty();
                    }
                    try {
                        return Optional.of(new Cached(rs.getLong(1), new CompanyFundamentals(
                                JsonMapper.INSTANCE.readValue(rs.getString(2), RATIOS),
                                quarter(rs, 3), quarter(rs, 4))));
                    } catch (JsonProcessingException e) {
                        // A row that cannot be read is as good as no row: it is refetched.
                        return Optional.<Cached>empty();
                    }
                }
            }
        });
    }

    @Override
    public void upsert(long instrumentId, long fetchedAtEpoch, CompanyFundamentals fundamentals) {
        String json;
        try {
            json = JsonMapper.INSTANCE.writeValueAsString(fundamentals.ratios());
        } catch (JsonProcessingException e) {
            throw new DataAccessException("Could not store fundamentals: " + e.getMessage(), e);
        }
        database.inTransaction(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT OR REPLACE INTO screener_fundamentals
                      (instrument_id, fetched_at, ratios_json, results_quarter, holding_quarter)
                    VALUES (?,?,?,?,?)""")) {
                upsert.setLong(1, instrumentId);
                upsert.setLong(2, fetchedAtEpoch);
                upsert.setString(3, json);
                upsert.setString(4, fundamentals.resultsQuarter() == null ? null
                        : fundamentals.resultsQuarter().toString());
                upsert.setString(5, fundamentals.holdingQuarter() == null ? null
                        : fundamentals.holdingQuarter().toString());
                upsert.executeUpdate();
            }
        });
    }

    private static YearMonth quarter(ResultSet rs, int column) throws SQLException {
        String text = rs.getString(column);
        return text == null ? null : YearMonth.parse(text);
    }
}
