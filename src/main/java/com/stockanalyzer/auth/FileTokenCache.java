package com.stockanalyzer.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockanalyzer.util.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Keeps the access token in a file so separate processes share it.
 *
 * <p>The file holds a live bearer token, so it is created owner-only and never
 * logged. Anything unreadable or malformed is treated as a cache miss - a
 * broken cache should cost one extra token request, not an outage.
 */
public final class FileTokenCache implements TokenCache {

    private static final Logger log = LoggerFactory.getLogger(FileTokenCache.class);

    private final Path file;

    public FileTokenCache(Path file) {
        this.file = file;
    }

    @Override
    public Optional<Entry> load(String key) {
        if (!Files.isReadable(file)) {
            return Optional.empty();
        }
        try {
            JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(file));
            if (!key.equals(root.path("key").asText(null))) {
                // A different credential wrote this; its token is no use here.
                return Optional.empty();
            }
            Entry entry = new Entry(root.path("token").asText(null),
                    Instant.ofEpochSecond(root.path("expiry_epoch").asLong(0)));
            return entry.token() == null || entry.token().isBlank()
                    ? Optional.empty()
                    : Optional.of(entry);
        } catch (Exception e) {
            log.debug("Ignoring unreadable token cache at {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void save(String key, Entry entry) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            String json = JsonMapper.INSTANCE.writeValueAsString(java.util.Map.of(
                    "key", key,
                    "token", entry.token(),
                    "expiry_epoch", entry.expiry().getEpochSecond()));
            Files.writeString(file, json);
            trySetOwnerOnly();
        } catch (Exception e) {
            // A cache that cannot be written is a performance problem, not a failure.
            log.warn("Could not write the token cache at {}: {}", file, e.getMessage());
        }
    }

    private void trySetOwnerOnly() {
        try {
            Files.setPosixFilePermissions(file,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (Exception e) {
            log.debug("Could not restrict permissions on {}: {}", file, e.getMessage());
        }
    }
}
