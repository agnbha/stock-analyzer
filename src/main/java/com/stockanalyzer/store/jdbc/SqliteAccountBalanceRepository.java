package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.model.AccountSnapshot;
import com.stockanalyzer.store.AccountBalanceRepository;
import com.stockanalyzer.store.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SqliteAccountBalanceRepository implements AccountBalanceRepository {

    private static final String UPSERT = """
            INSERT OR REPLACE INTO account_balance
              (session_date, cash, invested, total, source, recorded_at,
               margin_used, available, collateral, unpriced_holdings, fetched_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)""";

    private final Database database;

    public SqliteAccountBalanceRepository(Database database) {
        this.database = database;
    }

    @Override
    public void record(LocalDate sessionDate, double cash, Double invested, String source) {
        database.inTransaction(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement(UPSERT)) {
                upsert.setString(1, sessionDate.toString());
                upsert.setDouble(2, cash);
                JdbcSupport.setNullableDouble(upsert, 3, invested);
                JdbcSupport.setNullableDouble(upsert, 4, invested == null ? cash : cash + invested);
                upsert.setString(5, source);
                upsert.setLong(6, JdbcSupport.now());
                // A hand-entered balance knows nothing about margin or quotes;
                // leaving these null is what keeps it distinguishable from a reading.
                JdbcSupport.setNullableDouble(upsert, 7, null);
                JdbcSupport.setNullableDouble(upsert, 8, null);
                JdbcSupport.setNullableDouble(upsert, 9, null);
                upsert.setNull(10, java.sql.Types.INTEGER);
                upsert.setNull(11, java.sql.Types.INTEGER);
                upsert.executeUpdate();
            }
        });
    }

    @Override
    public void record(LocalDate sessionDate, AccountSnapshot snapshot) {
        database.inTransaction(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement(UPSERT)) {
                upsert.setString(1, sessionDate.toString());
                upsert.setDouble(2, snapshot.cash());
                upsert.setDouble(3, snapshot.holdingsValue());
                upsert.setDouble(4, snapshot.totalValue());
                upsert.setString(5, "broker");
                upsert.setLong(6, JdbcSupport.now());
                upsert.setDouble(7, snapshot.marginUsed());
                upsert.setDouble(8, snapshot.available());
                upsert.setDouble(9, snapshot.collateral());
                upsert.setLong(10, snapshot.unpricedHoldings());
                upsert.setLong(11, snapshot.fetchedAtEpoch());
                upsert.executeUpdate();
            }

            // Replace the day's holdings wholesale: a position sold today should
            // disappear, which an upsert on its own would never do.
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM account_holding WHERE session_date = ?")) {
                delete.setString(1, sessionDate.toString());
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO account_holding
                      (session_date, symbol, isin, quantity, average_price, last_price,
                       market_value, unrealised, recorded_at)
                    VALUES (?,?,?,?,?,?,?,?,?)""")) {
                long now = JdbcSupport.now();
                for (AccountSnapshot.Holding holding : snapshot.holdings()) {
                    insert.setString(1, sessionDate.toString());
                    insert.setString(2, holding.symbol());
                    insert.setString(3, holding.isin());
                    insert.setDouble(4, holding.quantity());
                    insert.setDouble(5, holding.averagePrice());
                    JdbcSupport.setNullableDouble(insert, 6, holding.lastPrice());
                    insert.setDouble(7, holding.value());
                    JdbcSupport.setNullableDouble(insert, 8, holding.unrealised());
                    insert.setLong(9, now);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        });
    }

    @Override
    public Optional<Balance> latestOnOrBefore(LocalDate date) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT * FROM account_balance WHERE session_date <= ? ORDER BY session_date DESC LIMIT 1")) {
                select.setString(1, date.toString());
                try (ResultSet rs = select.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<Balance>empty();
                }
            }
        });
    }

    @Override
    public List<Balance> findBetween(LocalDate from, LocalDate to) {
        return database.read(connection -> {
            List<Balance> balances = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT * FROM account_balance WHERE session_date BETWEEN ? AND ? ORDER BY session_date")) {
                select.setString(1, from.toString());
                select.setString(2, to.toString());
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        balances.add(map(rs));
                    }
                }
            }
            return balances;
        });
    }

    private static Balance map(ResultSet rs) throws SQLException {
        return new Balance(LocalDate.parse(rs.getString("session_date")), rs.getDouble("cash"),
                JdbcSupport.nullableDouble(rs, "invested"), JdbcSupport.nullableDouble(rs, "total"),
                rs.getString("source"), JdbcSupport.nullableDouble(rs, "margin_used"),
                JdbcSupport.nullableDouble(rs, "available"),
                JdbcSupport.nullableInt(rs, "unpriced_holdings"));
    }
}
