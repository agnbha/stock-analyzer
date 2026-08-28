package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.model.TradeAttribution;
import com.stockanalyzer.store.AttributionRepository;
import com.stockanalyzer.store.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public final class SqliteAttributionRepository implements AttributionRepository {

    private final Database database;

    public SqliteAttributionRepository(Database database) {
        this.database = database;
    }

    @Override
    public void upsertAll(List<TradeAttribution> attributions) {
        if (attributions.isEmpty()) {
            return;
        }
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT OR REPLACE INTO trade_attribution
                      (trade_id, gain_opportunity_id, alert_log_id, entry_lag_minutes, capture_pct)
                    VALUES (?,?,?,?,?)""")) {
                for (TradeAttribution a : attributions) {
                    insert.setLong(1, a.tradeId());
                    JdbcSupport.setNullableLong(insert, 2, a.gainOpportunityId());
                    JdbcSupport.setNullableLong(insert, 3, a.alertLogId());
                    JdbcSupport.setNullableInt(insert, 4, a.entryLagMinutes());
                    JdbcSupport.setNullableDouble(insert, 5, a.capturePct());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        });
    }

    @Override
    public List<TradeAttribution> findForTrades(List<Long> tradeIds) {
        if (tradeIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", tradeIds.stream().map(id -> "?").toList());
        return database.read(connection -> {
            List<TradeAttribution> rows = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT * FROM trade_attribution WHERE trade_id IN (" + placeholders + ")")) {
                for (int i = 0; i < tradeIds.size(); i++) {
                    select.setLong(i + 1, tradeIds.get(i));
                }
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new TradeAttribution(rs.getLong("trade_id"),
                                JdbcSupport.nullableLong(rs, "gain_opportunity_id"),
                                JdbcSupport.nullableLong(rs, "alert_log_id"),
                                JdbcSupport.nullableInt(rs, "entry_lag_minutes"),
                                JdbcSupport.nullableDouble(rs, "capture_pct")));
                    }
                }
            }
            return rows;
        });
    }
}
