package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.TradeReasonRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class SqliteTradeReasonRepository implements TradeReasonRepository {

    private final Database database;

    public SqliteTradeReasonRepository(Database database) {
        this.database = database;
    }

    @Override
    public void upsertAll(List<TradeReason> reasons) {
        if (reasons.isEmpty()) {
            return;
        }
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT OR REPLACE INTO trade_reason
                      (trade_id, reason_code, reason_source, detail, recorded_at)
                    VALUES (?,?,?,?,?)""")) {
                for (TradeReason reason : reasons) {
                    insert.setLong(1, reason.tradeId());
                    insert.setString(2, reason.reasonCode());
                    insert.setString(3, reason.source().name());
                    insert.setString(4, reason.detail());
                    insert.setLong(5, JdbcSupport.now());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        });
    }

    @Override
    public List<TradeReason> findBetween(LocalDate from, LocalDate to) {
        return database.read(connection -> {
            List<TradeReason> reasons = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT r.trade_id, r.reason_code, r.reason_source, r.detail
                    FROM trade_reason r JOIN trade t ON t.id = r.trade_id
                    WHERE t.session_date BETWEEN ? AND ?
                    ORDER BY t.executed_ts""")) {
                select.setString(1, from.toString());
                select.setString(2, to.toString());
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        reasons.add(new TradeReason(rs.getLong("trade_id"), rs.getString("reason_code"),
                                TradeReason.Source.valueOf(rs.getString("reason_source")),
                                rs.getString("detail")));
                    }
                }
            }
            return reasons;
        });
    }

    @Override
    public void deleteForTrades(List<Long> tradeIds) {
        if (tradeIds.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", tradeIds.stream().map(id -> "?").toList());
        database.inTransaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM trade_reason WHERE trade_id IN (" + placeholders + ")")) {
                for (int i = 0; i < tradeIds.size(); i++) {
                    delete.setLong(i + 1, tradeIds.get(i));
                }
                delete.executeUpdate();
            }
        });
    }
}
