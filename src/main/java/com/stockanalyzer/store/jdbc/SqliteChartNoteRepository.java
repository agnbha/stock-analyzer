package com.stockanalyzer.store.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.stockanalyzer.model.ChartNote;
import com.stockanalyzer.store.ChartNoteRepository;
import com.stockanalyzer.store.DataAccessException;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.util.JsonMapper;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SqliteChartNoteRepository implements ChartNoteRepository {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final Database database;

    public SqliteChartNoteRepository(Database database) {
        this.database = database;
    }

    @Override
    public int upsert(List<ChartNote> notes) {
        int[] added = {0};
        database.inTransaction(connection -> {
            Set<Long> existing = new HashSet<>();
            try (PreparedStatement select = connection.prepareStatement("SELECT grafana_id FROM chart_note");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    existing.add(rs.getLong(1));
                }
            }
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO chart_note (grafana_id, dashboard_uid, panel_id, symbol, symbol_source, noted_at,
                                            noted_to, sentiment, tags, note, synced_at, deleted_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,NULL)
                    ON CONFLICT (grafana_id) DO UPDATE SET
                      dashboard_uid = excluded.dashboard_uid, panel_id = excluded.panel_id,
                      symbol = CASE WHEN chart_note.symbol_source = 'MANUAL' THEN chart_note.symbol ELSE excluded.symbol END,
                      symbol_source = CASE WHEN chart_note.symbol_source = 'MANUAL' THEN 'MANUAL' ELSE excluded.symbol_source END,
                      noted_at = excluded.noted_at, noted_to = excluded.noted_to, sentiment = excluded.sentiment,
                      tags = excluded.tags, note = excluded.note, synced_at = excluded.synced_at, deleted_at = NULL""")) {
                for (ChartNote note : notes) {
                    if (!existing.contains(note.grafanaId())) {
                        added[0]++;
                    }
                    upsert.setLong(1, note.grafanaId());
                    upsert.setString(2, note.dashboardUid());
                    if (note.panelId() == null) {
                        upsert.setNull(3, java.sql.Types.INTEGER);
                    } else {
                        upsert.setInt(3, note.panelId());
                    }
                    upsert.setString(4, note.symbol());
                    upsert.setString(5, note.symbolSource());
                    upsert.setLong(6, note.notedAt());
                    if (note.notedTo() == null) {
                        upsert.setNull(7, java.sql.Types.INTEGER);
                    } else {
                        upsert.setLong(7, note.notedTo());
                    }
                    upsert.setString(8, note.sentiment());
                    upsert.setString(9, json(note.tags()));
                    upsert.setString(10, note.note());
                    upsert.setLong(11, note.syncedAt());
                    upsert.addBatch();
                }
                upsert.executeBatch();
            }
        });
        return added[0];
    }

    @Override
    public int markMissing(Set<Long> present, long nowEpoch) {
        int[] marked = {0};
        database.inTransaction(connection -> {
            List<Long> gone = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT grafana_id FROM chart_note WHERE deleted_at IS NULL");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    if (!present.contains(rs.getLong(1))) {
                        gone.add(rs.getLong(1));
                    }
                }
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE chart_note SET deleted_at = ? WHERE grafana_id = ?")) {
                for (long id : gone) {
                    update.setLong(1, nowEpoch);
                    update.setLong(2, id);
                    marked[0] += update.executeUpdate();
                }
            }
        });
        return marked[0];
    }

    @Override
    public boolean assignSymbol(long grafanaId, String symbol) {
        boolean[] found = {false};
        database.inTransaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE chart_note SET symbol = ?, symbol_source = 'MANUAL' WHERE grafana_id = ?")) {
                update.setString(1, symbol);
                update.setLong(2, grafanaId);
                found[0] = update.executeUpdate() > 0;
            }
        });
        return found[0];
    }

    @Override
    public List<ChartNote> findLive(String symbolOrNull) {
        return database.read(connection -> {
            List<ChartNote> found = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT grafana_id, dashboard_uid, panel_id, symbol, symbol_source, noted_at, noted_to, sentiment,
                           tags, note, synced_at, deleted_at
                    FROM chart_note
                    WHERE deleted_at IS NULL AND (? IS NULL OR symbol = ?)
                    ORDER BY noted_at, grafana_id""")) {
                select.setString(1, symbolOrNull);
                select.setString(2, symbolOrNull);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        found.add(new ChartNote(rs.getLong(1), rs.getString(2), (Integer) rs.getObject(3),
                                rs.getString(4), rs.getString(5), rs.getLong(6), (Long) rs.getObject(7), rs.getString(8),
                                tags(rs), rs.getString(10), rs.getLong(11), (Long) rs.getObject(12)));
                    }
                }
            }
            return found;
        });
    }

    private static List<String> tags(ResultSet rs) throws SQLException {
        try {
            return JsonMapper.INSTANCE.readValue(rs.getString(9), STRINGS);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private static String json(List<String> tags) {
        try {
            return JsonMapper.INSTANCE.writeValueAsString(tags);
        } catch (JsonProcessingException e) {
            throw new DataAccessException("Could not store a note's tags: " + e.getMessage(), e);
        }
    }
}
