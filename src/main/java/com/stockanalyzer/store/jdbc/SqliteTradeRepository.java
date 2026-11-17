package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.model.Product;
import com.stockanalyzer.model.Side;
import com.stockanalyzer.model.Trade;
import com.stockanalyzer.store.AccountProfileRepository;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.TradeRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SqliteTradeRepository implements TradeRepository {

    private static final String SELECT = """
            SELECT t.id, i.symbol, t.broker_trade_id, t.order_id, t.session_date, t.executed_ts, t.side,
                   t.quantity, t.price, t.product, t.charges_total, t.charges_json, t.charges_source,
                   t.source, t.notes
            FROM trade t JOIN instrument i ON i.id = t.instrument_id""";

    private final Database database;
    private final String profile;

    /** For the default profile; see {@link AccountProfileRepository#DEFAULT_PROFILE}. */
    public SqliteTradeRepository(Database database) {
        this(database, AccountProfileRepository.DEFAULT_PROFILE);
    }

    /** Sees and writes only the rows of {@code profile}. */
    public SqliteTradeRepository(Database database, String profile) {
        this.database = database;
        this.profile = profile;
    }

    @Override
    public int importAll(List<Trade> trades, String exchange, String segment) {
        if (trades.isEmpty()) {
            return 0;
        }
        return database.inTransaction(connection -> {
            int inserted = 0;
            try (PreparedStatement instrument = connection.prepareStatement(
                    "INSERT OR IGNORE INTO instrument (symbol, exchange, segment) VALUES (?,?,?)");
                 PreparedStatement insert = connection.prepareStatement("""
                         INSERT OR IGNORE INTO trade
                           (instrument_id, broker_trade_id, order_id, session_date, executed_ts, side, quantity,
                            price, product, charges_total, charges_json, charges_source, source, imported_at, notes,
                            profile)
                         VALUES ((SELECT id FROM instrument WHERE symbol = ? AND exchange = ? AND segment = ?),
                                 ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")) {
                for (Trade t : trades) {
                    instrument.setString(1, t.symbol());
                    instrument.setString(2, exchange);
                    instrument.setString(3, segment);
                    instrument.executeUpdate();

                    insert.setString(1, t.symbol());
                    insert.setString(2, exchange);
                    insert.setString(3, segment);
                    insert.setString(4, t.brokerTradeId());
                    insert.setString(5, t.orderId());
                    insert.setString(6, t.sessionDate().toString());
                    insert.setLong(7, t.executedTs());
                    insert.setString(8, t.side().name());
                    insert.setInt(9, t.quantity());
                    insert.setDouble(10, t.price());
                    insert.setString(11, t.product().name());
                    insert.setDouble(12, t.chargesTotal());
                    insert.setString(13, t.chargesJson());
                    insert.setString(14, t.chargesSource().name());
                    insert.setString(15, t.source().name());
                    insert.setLong(16, JdbcSupport.now());
                    insert.setString(17, t.notes());
                    insert.setString(18, profile);
                    inserted += insert.executeUpdate();
                }
            }
            return inserted;
        });
    }

    @Override
    public List<Trade> findRange(LocalDate from, LocalDate to) {
        return database.read(connection -> {
            List<Trade> trades = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    SELECT + " WHERE t.profile = ? AND t.session_date BETWEEN ? AND ? ORDER BY t.executed_ts, t.id")) {
                select.setString(1, profile);
                select.setString(2, from.toString());
                select.setString(3, to.toString());
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        trades.add(map(rs));
                    }
                }
            }
            return trades;
        });
    }

    @Override
    public List<Trade> findAllOrdered() {
        return database.read(connection -> {
            List<Trade> trades = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    SELECT + " WHERE t.profile = ? ORDER BY t.executed_ts, t.id")) {
                select.setString(1, profile);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        trades.add(map(rs));
                    }
                }
            }
            return trades;
        });
    }

    @Override
    public Optional<Trade> byId(long id) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(SELECT + " WHERE t.id = ? AND t.profile = ?")) {
                select.setLong(1, id);
                select.setString(2, profile);
                try (ResultSet rs = select.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<Trade>empty();
                }
            }
        });
    }

    private static Trade map(ResultSet rs) throws SQLException {
        return new Trade(
                rs.getLong("id"),
                rs.getString("symbol"),
                rs.getString("broker_trade_id"),
                rs.getString("order_id"),
                LocalDate.parse(rs.getString("session_date")),
                rs.getLong("executed_ts"),
                Side.valueOf(rs.getString("side")),
                rs.getInt("quantity"),
                rs.getDouble("price"),
                Product.valueOf(rs.getString("product")),
                rs.getDouble("charges_total"),
                rs.getString("charges_json"),
                Trade.ChargesSource.valueOf(rs.getString("charges_source")),
                Trade.TradeSource.valueOf(rs.getString("source")),
                rs.getString("notes"));
    }
}
