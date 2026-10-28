package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.WatchlistRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

public final class SqliteWatchlistRepository implements WatchlistRepository {

    private final Database database;

    public SqliteWatchlistRepository(Database database) {
        this.database = database;
    }

    @Override
    public Set<String> bullishSymbols(LocalDate before) {
        return symbols("""
                SELECT symbol FROM (
                  SELECT symbol, trend_score,
                         ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY session_date DESC) AS recency
                  FROM v_symbol_trend
                  WHERE session_date < ?)
                WHERE recency = 1 AND trend_score = 2""", before.toString());
    }

    @Override
    public Set<String> heldSymbols() {
        return symbols("""
                SELECT symbol FROM v_current_holdings WHERE quantity > 0
                UNION
                SELECT i.symbol FROM open_position p JOIN instrument i ON i.id = p.instrument_id
                WHERE p.quantity > 0""");
    }

    private Set<String> symbols(String sql, String... parameters) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(sql)) {
                for (int i = 0; i < parameters.length; i++) {
                    select.setString(i + 1, parameters[i]);
                }
                Set<String> found = new HashSet<>();
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        found.add(rs.getString(1));
                    }
                }
                return found;
            }
        });
    }
}
