package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.IndexMemberRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SqliteIndexMemberRepository implements IndexMemberRepository {

    private final Database database;

    public SqliteIndexMemberRepository(Database database) {
        this.database = database;
    }

    @Override
    public void replace(String index, int position, List<String> symbols, String exchange, String source, long nowEpoch) {
        database.inTransaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM index_member WHERE index_name = ?")) {
                delete.setString(1, index);
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT OR IGNORE INTO index_member (index_name, symbol, exchange, index_position, source, fetched_at)
                    VALUES (?,?,?,?,?,?)""")) {
                for (String symbol : symbols) {
                    insert.setString(1, index);
                    insert.setString(2, symbol);
                    insert.setString(3, exchange);
                    insert.setInt(4, position);
                    insert.setString(5, source);
                    insert.setLong(6, nowEpoch);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        });
    }

    @Override
    public void reorder(String index, int position) {
        database.inTransaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE index_member SET index_position = ? WHERE index_name = ?")) {
                update.setInt(1, position);
                update.setString(2, index);
                update.executeUpdate();
            }
        });
    }

    @Override
    public int retainOnly(Collection<String> indices) {
        Set<String> keep = Set.copyOf(indices);
        int[] dropped = {0};
        database.inTransaction(connection -> {
            List<String> held = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("SELECT DISTINCT index_name FROM index_member");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    held.add(rs.getString(1));
                }
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM index_member WHERE index_name = ?")) {
                for (String index : held) {
                    if (!keep.contains(index)) {
                        delete.setString(1, index);
                        delete.executeUpdate();
                        dropped[0]++;
                    }
                }
            }
        });
        return dropped[0];
    }

    @Override
    public Map<String, Integer> counts() {
        return database.read(connection -> {
            Map<String, Integer> counts = new LinkedHashMap<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT index_name, COUNT(*) FROM index_member GROUP BY index_name
                    ORDER BY MIN(index_position), index_name""");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString(1), rs.getInt(2));
                }
            }
            return counts;
        });
    }

    @Override
    public List<BarSpan> barSpans(String segment) {
        return database.read(connection -> {
            List<BarSpan> spans = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT m.symbol, m.exchange, MIN(b.session_date), MAX(b.session_date)
                    FROM (SELECT DISTINCT symbol, exchange FROM index_member) m
                    LEFT JOIN instrument i ON i.symbol = m.symbol AND i.exchange = m.exchange AND i.segment = ?
                    LEFT JOIN daily_bar b ON b.instrument_id = i.id
                    GROUP BY m.symbol, m.exchange
                    ORDER BY m.symbol""")) {
                select.setString(1, segment);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        spans.add(new BarSpan(rs.getString(1), rs.getString(2), date(rs.getString(3)), date(rs.getString(4))));
                    }
                }
            }
            return spans;
        });
    }

    @Override
    public Optional<LocalDate> latestSession() {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("SELECT MAX(session_date) FROM daily_bar");
                 ResultSet rs = select.executeQuery()) {
                return Optional.ofNullable(rs.next() ? date(rs.getString(1)) : null);
            }
        });
    }

    private static LocalDate date(String text) {
        return text == null ? null : LocalDate.parse(text);
    }
}
