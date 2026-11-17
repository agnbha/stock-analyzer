package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.model.OpenPosition;
import com.stockanalyzer.model.Product;
import com.stockanalyzer.model.RealizedLot;
import com.stockanalyzer.store.AccountProfileRepository;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.RealizedLotRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class SqliteRealizedLotRepository implements RealizedLotRepository {

    private final Database database;
    private final String profile;

    /** For the default profile; see {@link AccountProfileRepository#DEFAULT_PROFILE}. */
    public SqliteRealizedLotRepository(Database database) {
        this(database, AccountProfileRepository.DEFAULT_PROFILE);
    }

    /** Sees and writes only the rows of {@code profile}. */
    public SqliteRealizedLotRepository(Database database, String profile) {
        this.database = database;
        this.profile = profile;
    }

    @Override
    public void replaceAll(List<RealizedLot> lots, List<OpenPosition> openPositions, String exchange, String segment) {
        database.inTransaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM realized_lot WHERE profile = ?")) {
                delete.setString(1, profile);
                delete.executeUpdate();
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM open_position WHERE profile = ?")) {
                delete.setString(1, profile);
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO realized_lot
                      (instrument_id, buy_trade_id, sell_trade_id, product, quantity, buy_price, sell_price,
                       opened_ts, closed_ts, holding_minutes, gross_pnl, charges_allocated, net_pnl, return_pct, profile)
                    VALUES ((SELECT id FROM instrument WHERE symbol = ? AND exchange = ? AND segment = ?),
                            ?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")) {
                for (RealizedLot lot : lots) {
                    insert.setString(1, lot.symbol());
                    insert.setString(2, exchange);
                    insert.setString(3, segment);
                    insert.setLong(4, lot.buyTradeId());
                    insert.setLong(5, lot.sellTradeId());
                    insert.setString(6, lot.product().name());
                    insert.setInt(7, lot.quantity());
                    insert.setDouble(8, lot.buyPrice());
                    insert.setDouble(9, lot.sellPrice());
                    insert.setLong(10, lot.openedTs());
                    insert.setLong(11, lot.closedTs());
                    insert.setInt(12, lot.holdingMinutes());
                    insert.setDouble(13, lot.grossPnl());
                    insert.setDouble(14, lot.chargesAllocated());
                    insert.setDouble(15, lot.netPnl());
                    insert.setDouble(16, lot.returnPct());
                    insert.setString(17, profile);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO open_position (instrument_id, product, quantity, avg_cost, opened_ts, last_updated,
                                               profile)
                    VALUES ((SELECT id FROM instrument WHERE symbol = ? AND exchange = ? AND segment = ?),
                            ?,?,?,?,?,?)""")) {
                for (OpenPosition p : openPositions) {
                    insert.setString(1, p.symbol());
                    insert.setString(2, exchange);
                    insert.setString(3, segment);
                    insert.setString(4, p.product().name());
                    insert.setInt(5, p.quantity());
                    insert.setDouble(6, p.avgCost());
                    insert.setLong(7, p.openedTs());
                    insert.setLong(8, JdbcSupport.now());
                    insert.setString(9, profile);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        });
    }

    @Override
    public List<RealizedLot> findClosedBetween(LocalDate from, LocalDate to) {
        return database.read(connection -> {
            List<RealizedLot> lots = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT l.*, i.symbol FROM realized_lot l
                    JOIN instrument i ON i.id = l.instrument_id
                    JOIN trade s ON s.id = l.sell_trade_id
                    WHERE l.profile = ? AND s.session_date BETWEEN ? AND ?
                    ORDER BY l.closed_ts""")) {
                select.setString(1, profile);
                select.setString(2, from.toString());
                select.setString(3, to.toString());
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        lots.add(map(rs));
                    }
                }
            }
            return lots;
        });
    }

    @Override
    public List<OpenPosition> openPositions() {
        return database.read(connection -> {
            List<OpenPosition> positions = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT i.symbol, p.product, p.quantity, p.avg_cost, p.opened_ts
                    FROM open_position p JOIN instrument i ON i.id = p.instrument_id
                    WHERE p.profile = ? ORDER BY i.symbol""")) {
                select.setString(1, profile);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        positions.add(new OpenPosition(rs.getString("symbol"),
                                Product.valueOf(rs.getString("product")), rs.getInt("quantity"),
                                rs.getDouble("avg_cost"), rs.getLong("opened_ts")));
                    }
                }
            }
            return positions;
        });
    }

    private static RealizedLot map(ResultSet rs) throws SQLException {
        return new RealizedLot(rs.getLong("id"), rs.getString("symbol"), rs.getLong("buy_trade_id"),
                rs.getLong("sell_trade_id"), Product.valueOf(rs.getString("product")), rs.getInt("quantity"),
                rs.getDouble("buy_price"), rs.getDouble("sell_price"), rs.getLong("opened_ts"),
                rs.getLong("closed_ts"), rs.getInt("holding_minutes"), rs.getDouble("gross_pnl"),
                rs.getDouble("charges_allocated"), rs.getDouble("net_pnl"), rs.getDouble("return_pct"));
    }
}
