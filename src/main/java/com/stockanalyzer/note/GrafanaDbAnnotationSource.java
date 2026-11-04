package com.stockanalyzer.note;

import com.fasterxml.jackson.core.type.TypeReference;
import com.stockanalyzer.util.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the notes straight out of Grafana's own database file, which needs no login and no running server.
 *
 * <p>The file is copied first (with its write-ahead log), and the copy is read: Grafana may be writing to the
 * original, and opening a live SQLite file for reading from another process is not worth the risk for a
 * database of a few megabytes. This works where Grafana runs locally; under Docker the database is inside a
 * volume, so point {@code grafana.db.path} at a copy of it, or at the volume's mount.
 */
public final class GrafanaDbAnnotationSource implements GrafanaAnnotationSource {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final Path database;

    public GrafanaDbAnnotationSource(Path database) {
        this.database = database;
    }

    @Override
    public List<GrafanaAnnotation> fetch() {
        if (!Files.isReadable(database)) {
            throw new Unavailable("Grafana's database is not at " + database
                    + " (set grafana.db.path to where it is)");
        }
        Path copy = null;
        try {
            copy = Files.createTempFile("grafana-annotations-", ".db");
            Files.copy(database, copy, StandardCopyOption.REPLACE_EXISTING);
            Path wal = Path.of(database + "-wal");
            if (Files.isReadable(wal)) {
                Files.copy(wal, Path.of(copy + "-wal"), StandardCopyOption.REPLACE_EXISTING);
            }
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + copy)) {
                return read(connection);
            }
        } catch (IOException | SQLException e) {
            throw new Unavailable("Could not read Grafana's database at " + database + ": " + e.getMessage(), e);
        } finally {
            if (copy != null) {
                for (String suffix : new String[]{"", "-wal", "-shm", "-journal"}) {
                    try {
                        Files.deleteIfExists(Path.of(copy + suffix));
                    } catch (IOException ignored) {
                        // a temporary file that cannot be removed is the operating system's to clean up
                    }
                }
            }
        }
    }

    private static List<GrafanaAnnotation> read(Connection connection) throws SQLException {
        List<GrafanaAnnotation> found = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT a.id, d.uid, a.panel_id, a.epoch, a.epoch_end, a.text, a.tags
                FROM annotation a LEFT JOIN dashboard d ON d.id = a.dashboard_id
                WHERE a.alert_id = 0 AND (a.type IS NULL OR a.type = '')
                ORDER BY a.id""");
             ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                int panel = rs.getInt(3);
                Integer panelId = rs.wasNull() ? null : panel;
                long epoch = rs.getLong(4);
                long epochEnd = rs.getLong(5);
                found.add(new GrafanaAnnotation(rs.getLong(1), rs.getString(2), panelId, epoch,
                        epochEnd == 0 ? epoch : epochEnd, rs.getString(6), tags(rs.getString(7))));
            }
        }
        return found;
    }

    private static List<String> tags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JsonMapper.INSTANCE.readValue(json, STRINGS);
        } catch (IOException e) {
            return List.of();
        }
    }
}
