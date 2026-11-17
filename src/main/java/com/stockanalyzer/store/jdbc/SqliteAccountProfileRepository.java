package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.store.AccountProfileRepository;
import com.stockanalyzer.store.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SqliteAccountProfileRepository implements AccountProfileRepository {

    private final Database database;

    public SqliteAccountProfileRepository(Database database) {
        this.database = database;
    }

    @Override
    public void register(List<String> namesInOrder) {
        database.inTransaction(connection -> {
            long now = JdbcSupport.now();
            // Profiles no longer configured go after the configured ones, keeping their relative order.
            try (PreparedStatement demote = connection.prepareStatement(
                    "UPDATE account_profile SET position = position + ? WHERE position < ?")) {
                demote.setInt(1, namesInOrder.size() + 1000);
                demote.setInt(2, 1000);
                demote.executeUpdate();
            }
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO account_profile (name, position, updated_at) VALUES (?,?,?)
                    ON CONFLICT (name) DO UPDATE SET position = excluded.position, updated_at = excluded.updated_at""")) {
                for (int i = 0; i < namesInOrder.size(); i++) {
                    upsert.setString(1, namesInOrder.get(i));
                    upsert.setInt(2, i);
                    upsert.setLong(3, now);
                    upsert.executeUpdate();
                }
            }
        });
    }

    @Override
    public Map<String, Integer> adoptUnassigned(String profile) {
        return move("", profile);
    }

    @Override
    public Map<String, Integer> rename(String from, String to) {
        if (from.equals(to)) {
            return Map.of();
        }
        return database.inTransaction(connection -> {
            for (String table : TABLES) {
                try (PreparedStatement taken = connection.prepareStatement(
                        "SELECT 1 FROM " + table + " WHERE profile = ? LIMIT 1")) {
                    taken.setString(1, to);
                    try (ResultSet rs = taken.executeQuery()) {
                        if (rs.next()) {
                            throw new IllegalArgumentException("Profile '" + to + "' already has rows in " + table
                                    + "; renaming '" + from + "' to it would merge two accounts' journals");
                        }
                    }
                }
            }
            Map<String, Integer> moved = moveWithin(connection, from, to);
            try (PreparedStatement rename = connection.prepareStatement(
                    "UPDATE OR REPLACE account_profile SET name = ? WHERE name = ?")) {
                rename.setString(1, to);
                rename.setString(2, from);
                rename.executeUpdate();
            }
            return moved;
        });
    }

    private Map<String, Integer> move(String from, String to) {
        Database.SqlFunction<Map<String, Integer>> work = connection -> moveWithin(connection, from, to);
        return database.inTransaction(work);
    }

    /** UPDATE OR REPLACE: a row already under {@code to} for the same key gives way to the one moving in. */
    private static Map<String, Integer> moveWithin(java.sql.Connection connection, String from, String to)
            throws java.sql.SQLException {
        Map<String, Integer> moved = new LinkedHashMap<>();
        for (String table : TABLES) {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE OR REPLACE " + table + " SET profile = ? WHERE profile = ?")) {
                update.setString(1, to);
                update.setString(2, from);
                int rows = update.executeUpdate();
                if (rows > 0) {
                    moved.put(table, rows);
                }
            }
        }
        return moved;
    }

    @Override
    public List<Summary> summaries() {
        return database.read(connection -> {
            Map<String, Integer> positions = new LinkedHashMap<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT name, position FROM account_profile ORDER BY position, name");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    positions.put(rs.getString("name"), rs.getInt("position"));
                }
            }
            Set<String> names = new LinkedHashSet<>(positions.keySet());
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT profile FROM trade UNION SELECT profile FROM account_balance ORDER BY 1""");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
            List<Summary> summaries = new ArrayList<>();
            for (String name : names) {
                Integer position = positions.get(name);
                summaries.add(new Summary(name, position != null && position < 1000 ? position : null,
                        count(connection, "SELECT COUNT(*) FROM trade WHERE profile = ?", name),
                        count(connection, "SELECT COUNT(*) FROM open_position WHERE profile = ?", name),
                        text(connection, "SELECT MAX(session_date) FROM trade WHERE profile = ?", name),
                        text(connection, "SELECT MAX(session_date) FROM account_balance WHERE profile = ?", name)));
            }
            return summaries;
        });
    }

    private static int count(java.sql.Connection connection, String sql, String profile) throws java.sql.SQLException {
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, profile);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static String text(java.sql.Connection connection, String sql, String profile) throws java.sql.SQLException {
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, profile);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
