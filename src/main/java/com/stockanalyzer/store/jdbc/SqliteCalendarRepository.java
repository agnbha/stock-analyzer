package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.store.CalendarRepository;
import com.stockanalyzer.store.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

public final class SqliteCalendarRepository implements CalendarRepository {

    private final Database database;

    public SqliteCalendarRepository(Database database) {
        this.database = database;
    }

    @Override
    public void markNonTrading(LocalDate date, String reason) {
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT OR REPLACE INTO non_trading_day (session_date, reason, observed_at) VALUES (?,?,?)")) {
                insert.setString(1, date.toString());
                insert.setString(2, reason);
                insert.setLong(3, JdbcSupport.now());
                insert.executeUpdate();
            }
        });
    }

    @Override
    public Set<LocalDate> datesWithTradingEvidence(LocalDate from, LocalDate to) {
        return database.read(connection -> {
            Set<LocalDate> dates = new LinkedHashSet<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT session_date FROM trading_day WHERE session_date BETWEEN ? AND ?
                    UNION
                    SELECT session_date FROM daily_bar WHERE session_date BETWEEN ? AND ?
                    GROUP BY session_date HAVING COUNT(*) >= ?""")) {
                select.setString(1, from.toString());
                select.setString(2, to.toString());
                select.setString(3, from.toString());
                select.setString(4, to.toString());
                select.setInt(5, MIN_DAILY_BARS_AS_EVIDENCE);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        dates.add(LocalDate.parse(rs.getString(1)));
                    }
                }
            }
            return dates;
        });
    }

    @Override
    public Set<LocalDate> nonTradingDays(LocalDate from, LocalDate to) {
        return database.read(connection -> {
            Set<LocalDate> dates = new LinkedHashSet<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT session_date FROM non_trading_day WHERE session_date BETWEEN ? AND ?")) {
                select.setString(1, from.toString());
                select.setString(2, to.toString());
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        dates.add(LocalDate.parse(rs.getString(1)));
                    }
                }
            }
            return dates;
        });
    }
}
