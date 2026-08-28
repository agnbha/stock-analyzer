package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.model.PeriodType;
import com.stockanalyzer.model.PnlSummary;
import com.stockanalyzer.model.Product;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.PnlPeriodRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.Optional;

/**
 * SQLite treats NULLs as distinct in a UNIQUE index, so the "all symbols" rows
 * are replaced with an explicit delete-then-insert rather than ON CONFLICT.
 */
public final class SqlitePnlPeriodRepository implements PnlPeriodRepository {

    private final Database database;

    public SqlitePnlPeriodRepository(Database database) {
        this.database = database;
    }

    @Override
    public void upsert(PnlSummary s) {
        database.inTransaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM pnl_period WHERE period_type = ? AND period_start = ?
                      AND instrument_id IS (SELECT id FROM instrument WHERE symbol = ?)
                      AND product IS ?""")) {
                delete.setString(1, s.periodType().name());
                delete.setString(2, s.periodStart().toString());
                delete.setString(3, s.symbol());
                delete.setString(4, s.product() == null ? null : s.product().name());
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO pnl_period
                      (period_type, period_start, period_end, instrument_id, product, trades, closed_lots,
                       wins, losses, win_rate, gross_pnl, charges, net_pnl, turnover, charges_pct_turnover,
                       avg_win, avg_loss, profit_factor, best_lot_pnl, worst_lot_pnl, unrealized_end, computed_at)
                    VALUES (?,?,?,(SELECT id FROM instrument WHERE symbol = ?),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")) {
                insert.setString(1, s.periodType().name());
                insert.setString(2, s.periodStart().toString());
                insert.setString(3, s.periodEnd().toString());
                insert.setString(4, s.symbol());
                insert.setString(5, s.product() == null ? null : s.product().name());
                insert.setInt(6, s.trades());
                insert.setInt(7, s.closedLots());
                insert.setInt(8, s.wins());
                insert.setInt(9, s.losses());
                insert.setDouble(10, s.winRate());
                insert.setDouble(11, s.grossPnl());
                insert.setDouble(12, s.charges());
                insert.setDouble(13, s.netPnl());
                insert.setDouble(14, s.turnover());
                insert.setDouble(15, s.chargesPctTurnover());
                insert.setDouble(16, s.avgWin());
                insert.setDouble(17, s.avgLoss());
                insert.setDouble(18, s.profitFactor());
                insert.setDouble(19, s.bestLotPnl());
                insert.setDouble(20, s.worstLotPnl());
                insert.setDouble(21, s.unrealizedEnd());
                insert.setLong(22, JdbcSupport.now());
                insert.executeUpdate();
            }
        });
    }

    @Override
    public Optional<PnlSummary> find(PeriodType periodType, LocalDate periodStart, String symbolOrNull) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT p.*, i.symbol AS symbol FROM pnl_period p
                    LEFT JOIN instrument i ON i.id = p.instrument_id
                    WHERE p.period_type = ? AND p.period_start = ?
                      AND p.instrument_id IS (SELECT id FROM instrument WHERE symbol = ?)""")) {
                select.setString(1, periodType.name());
                select.setString(2, periodStart.toString());
                select.setString(3, symbolOrNull);
                try (ResultSet rs = select.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<PnlSummary>empty();
                    }
                    String product = rs.getString("product");
                    return Optional.of(new PnlSummary(
                            PeriodType.valueOf(rs.getString("period_type")),
                            LocalDate.parse(rs.getString("period_start")),
                            LocalDate.parse(rs.getString("period_end")),
                            rs.getString("symbol"),
                            product == null ? null : Product.valueOf(product),
                            rs.getInt("trades"), rs.getInt("closed_lots"), rs.getInt("wins"), rs.getInt("losses"),
                            rs.getDouble("win_rate"), rs.getDouble("gross_pnl"), rs.getDouble("charges"),
                            rs.getDouble("net_pnl"), rs.getDouble("turnover"), rs.getDouble("charges_pct_turnover"),
                            rs.getDouble("avg_win"), rs.getDouble("avg_loss"), rs.getDouble("profit_factor"),
                            rs.getDouble("best_lot_pnl"), rs.getDouble("worst_lot_pnl"),
                            rs.getDouble("unrealized_end")));
                }
            }
        });
    }
}
