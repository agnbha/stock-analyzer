package com.stockanalyzer.store;

import java.nio.file.Files;
import java.nio.file.Path;

/** A migrated, throwaway database file per test. */
public final class TestDatabase {

    private TestDatabase() {
    }

    public static Database open(Path directory) {
        try {
            Path file = Files.createTempFile(directory, "stock-analyzer-test", ".db");
            Files.deleteIfExists(file);
            Database database = Database.open("jdbc:sqlite:" + file, 5000);
            new SchemaMigrator(database).migrate();
            return database;
        } catch (Exception e) {
            throw new IllegalStateException("Could not create a test database", e);
        }
    }
}
