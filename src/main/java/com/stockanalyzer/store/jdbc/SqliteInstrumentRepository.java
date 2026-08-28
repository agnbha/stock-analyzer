package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.model.Instrument;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.InstrumentRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SqliteInstrumentRepository implements InstrumentRepository {

    private final Database database;

    public SqliteInstrumentRepository(Database database) {
        this.database = database;
    }

    @Override
    public long findOrCreate(String symbol, String exchange, String segment) {
        return database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT OR IGNORE INTO instrument (symbol, exchange, segment) VALUES (?, ?, ?)")) {
                insert.setString(1, symbol);
                insert.setString(2, exchange);
                insert.setString(3, segment);
                insert.executeUpdate();
            }
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id FROM instrument WHERE symbol = ? AND exchange = ? AND segment = ?")) {
                select.setString(1, symbol);
                select.setString(2, exchange);
                select.setString(3, segment);
                try (ResultSet rs = select.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    @Override
    public Optional<Instrument> find(String symbol, String exchange, String segment) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id, symbol, exchange, segment, name FROM instrument "
                            + "WHERE symbol = ? AND exchange = ? AND segment = ?")) {
                select.setString(1, symbol);
                select.setString(2, exchange);
                select.setString(3, segment);
                try (ResultSet rs = select.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<Instrument>empty();
                }
            }
        });
    }

    @Override
    public Optional<Instrument> byId(long id) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id, symbol, exchange, segment, name FROM instrument WHERE id = ?")) {
                select.setLong(1, id);
                try (ResultSet rs = select.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<Instrument>empty();
                }
            }
        });
    }

    @Override
    public List<Instrument> findAll() {
        return database.read(connection -> {
            List<Instrument> all = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id, symbol, exchange, segment, name FROM instrument ORDER BY symbol");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    all.add(map(rs));
                }
            }
            return all;
        });
    }

    private static Instrument map(ResultSet rs) throws java.sql.SQLException {
        return new Instrument(rs.getLong("id"), rs.getString("symbol"), rs.getString("exchange"),
                rs.getString("segment"), rs.getString("name"));
    }
}
