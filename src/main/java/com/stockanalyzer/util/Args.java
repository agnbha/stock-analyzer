package com.stockanalyzer.util;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Minimal {@code --key value} / {@code --flag} parsing for the command line entry points. */
public final class Args {

    private final List<String> positional;
    private final Map<String, String> options;

    private Args(List<String> positional, Map<String, String> options) {
        this.positional = positional;
        this.options = options;
    }

    public static Args parse(String[] argv) {
        List<String> positional = new java.util.ArrayList<>();
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < argv.length; i++) {
            String token = argv[i];
            if (!token.startsWith("--")) {
                positional.add(token);
                continue;
            }
            String key = token.substring(2);
            if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                options.put(key, argv[++i]);
            } else {
                options.put(key, "true");
            }
        }
        return new Args(List.copyOf(positional), Map.copyOf(options));
    }

    public String command(String fallback) {
        return positional.isEmpty() ? fallback : positional.getFirst();
    }

    public String subcommand(String fallback) {
        return positional.size() < 2 ? fallback : positional.get(1);
    }

    public boolean has(String key) {
        return options.containsKey(key);
    }

    public boolean flag(String key) {
        return Boolean.parseBoolean(options.getOrDefault(key, "false"));
    }

    public Optional<String> value(String key) {
        return Optional.ofNullable(options.get(key));
    }

    public String require(String key) {
        return value(key).orElseThrow(() ->
                new IllegalArgumentException("Missing required option --" + key));
    }

    public LocalDate date(String key, LocalDate fallback) {
        return value(key).map(LocalDate::parse).orElse(fallback);
    }

    public int integer(String key, int fallback) {
        return value(key).map(Integer::parseInt).orElse(fallback);
    }
}
