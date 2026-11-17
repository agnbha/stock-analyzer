package com.stockanalyzer.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads a shell env file of the kind {@code ops/bin/common.sh} sources: {@code KEY=value} lines, optionally prefixed
 * with {@code export}, values bare or in single or double quotes, {@code #} comments and blank lines ignored. It is
 * read as data, never run, so nothing in it is expanded; a key set twice keeps its last value, as the shell would.
 */
public final class EnvFile {

    private EnvFile() {
    }

    public static Map<String, String> read(Path file) {
        try {
            return parse(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    static Map<String, String> parse(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).strip();
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = line.substring(0, equals).strip();
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                continue;
            }
            values.put(key, value(line.substring(equals + 1).strip()));
        }
        return values;
    }

    /** A quoted value runs to its closing quote; a bare one to the first space, as the shell reads it. */
    private static String value(String text) {
        if (text.length() >= 1 && (text.charAt(0) == '"' || text.charAt(0) == '\'')) {
            int close = text.indexOf(text.charAt(0), 1);
            return close < 0 ? text.substring(1) : text.substring(1, close);
        }
        int space = text.indexOf(' ');
        int tab = text.indexOf('\t');
        int end = space < 0 ? tab : tab < 0 ? space : Math.min(space, tab);
        return end < 0 ? text : text.substring(0, end);
    }
}
