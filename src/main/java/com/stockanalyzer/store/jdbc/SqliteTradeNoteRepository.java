package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.model.Side;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.TradeNoteRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class SqliteTradeNoteRepository implements TradeNoteRepository {

    private final Database database;

    public SqliteTradeNoteRepository(Database database) {
        this.database = database;
    }

    @Override
    public void upsertAll(List<Long> tradeIds, String sentiment, String note, long notedAtEpoch) {
        database.inTransaction(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO trade_note (trade_id, sentiment, note, noted_at) VALUES (?,?,?,?)
                    ON CONFLICT (trade_id) DO UPDATE SET
                      sentiment = excluded.sentiment, note = excluded.note, noted_at = excluded.noted_at""")) {
                for (long tradeId : tradeIds) {
                    upsert.setLong(1, tradeId);
                    upsert.setString(2, sentiment);
                    upsert.setString(3, note);
                    upsert.setLong(4, notedAtEpoch);
                    upsert.addBatch();
                }
                upsert.executeBatch();
            }
        });
    }

    @Override
    public int deleteAll(List<Long> tradeIds) {
        int[] removed = {0};
        database.inTransaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM trade_note WHERE trade_id = ?")) {
                for (long tradeId : tradeIds) {
                    delete.setLong(1, tradeId);
                    removed[0] += delete.executeUpdate();
                }
            }
        });
        return removed[0];
    }

    @Override
    public List<Noted> findBetween(LocalDate from, LocalDate to, String symbolOrNull) {
        return database.read(connection -> {
            List<Noted> found = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT t.id, i.symbol, t.side, t.session_date, t.executed_ts, t.quantity, t.price,
                           n.sentiment, n.note
                    FROM trade_note n
                    JOIN trade t ON t.id = n.trade_id
                    JOIN instrument i ON i.id = t.instrument_id
                    WHERE t.session_date BETWEEN ? AND ? AND (? IS NULL OR i.symbol = ?)
                    ORDER BY t.executed_ts, t.id""")) {
                select.setString(1, from.toString());
                select.setString(2, to.toString());
                select.setString(3, symbolOrNull);
                select.setString(4, symbolOrNull);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        found.add(new Noted(rs.getLong(1), rs.getString(2), Side.valueOf(rs.getString(3)),
                                LocalDate.parse(rs.getString(4)), rs.getLong(5), rs.getInt(6), rs.getDouble(7),
                                rs.getString(8), rs.getString(9)));
                    }
                }
            }
            return found;
        });
    }
}
