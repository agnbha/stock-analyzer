package com.stockanalyzer.alert;

import com.stockanalyzer.model.Alert;
import com.stockanalyzer.util.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Appends alerts as JSON lines, so a day's alerts can be analysed later. */
public final class FileAlertSink implements AlertSink {

    private static final Logger log = LoggerFactory.getLogger(FileAlertSink.class);

    private final Path file;

    public FileAlertSink(Path file) {
        this.file = file;
    }

    @Override
    public void publish(Alert alert) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            String line = JsonMapper.INSTANCE.writeValueAsString(alert) + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("Could not append alert to {}: {}", file, e.getMessage());
        }
    }
}
