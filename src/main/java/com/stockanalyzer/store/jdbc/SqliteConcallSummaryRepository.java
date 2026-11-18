package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.canslim.ConcallSummary;
import com.stockanalyzer.store.ConcallSummaryRepository;
import com.stockanalyzer.store.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

public final class SqliteConcallSummaryRepository implements ConcallSummaryRepository {

    private final Database database;

    public SqliteConcallSummaryRepository(Database database) {
        this.database = database;
    }

    @Override
    public void save(long instrumentId, YearMonth call, String sourceUrl, List<ConcallSummary.Line> lines,
                     long fetchedAtEpoch) {
        database.inTransaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM company_concall_summary WHERE instrument_id = ? AND period = ?")) {
                delete.setLong(1, instrumentId);
                delete.setString(2, call.toString());
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO company_concall_summary
                      (instrument_id, period, line_no, depth, text, source_url, fetched_at)
                    VALUES (?,?,?,?,?,?,?)""")) {
                int number = 1;
                for (ConcallSummary.Line line : lines) {
                    insert.setLong(1, instrumentId);
                    insert.setString(2, call.toString());
                    insert.setInt(3, number++);
                    insert.setInt(4, line.depth());
                    insert.setString(5, line.text());
                    insert.setString(6, sourceUrl);
                    insert.setLong(7, fetchedAtEpoch);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        });
    }

    @Override
    public Optional<Long> fetchedAt(long instrumentId, YearMonth call) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT MAX(fetched_at) FROM company_concall_summary WHERE instrument_id = ? AND period = ?")) {
                select.setLong(1, instrumentId);
                select.setString(2, call.toString());
                try (ResultSet rs = select.executeQuery()) {
                    long at = rs.next() ? rs.getLong(1) : 0;
                    return rs.wasNull() || at == 0 ? Optional.empty() : Optional.of(at);
                }
            }
        });
    }
}
