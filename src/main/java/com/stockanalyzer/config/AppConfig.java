package com.stockanalyzer.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Properties;

/**
 * Typed access to application configuration. Values are read from
 * (in increasing priority) {@code application.properties} on the classpath,
 * then environment variables, so secrets never need to be committed to the repo.
 */
public final class AppConfig {

    private final Properties properties;

    private AppConfig(Properties properties) {
        this.properties = properties;
    }

    public static AppConfig load() {
        Properties props = new Properties();
        try (InputStream in = AppConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load application.properties", e);
        }
        return new AppConfig(props);
    }

    private String get(String key, String defaultValue) {
        String envKey = key.toUpperCase().replace('.', '_');
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        return properties.getProperty(key, defaultValue);
    }

    private String require(String key) {
        String value = get(key, null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing required config '" + key + "'. Set it in application.properties or as env var "
                            + key.toUpperCase().replace('.', '_') + ".");
        }
        return value;
    }

    public String growwApiKey() {
        return require("groww.api.key");
    }

    public String growwApiSecret() {
        return require("groww.api.secret");
    }

    public String growwBaseUrl() {
        return get("groww.base.url", "https://api.groww.in/v1");
    }

    public String exchange() {
        return get("groww.exchange", "NSE");
    }

    public String segment() {
        return get("groww.segment", "CASH");
    }

    /** Candle interval in minutes; 1440 = daily. */
    public int candleIntervalMinutes() {
        return Integer.parseInt(get("groww.candle.interval.minutes", "1440"));
    }

    public int lookbackDays() {
        return Integer.parseInt(get("groww.lookback.days", "30"));
    }

    /** Candle window used by {@link com.stockanalyzer.client.HistoricalFundamentalsClient} to derive week-52 high/low. */
    public int fundamentalsLookbackDays() {
        return Integer.parseInt(get("groww.fundamentals.lookback.days", "365"));
    }

    public String mlServiceUrl() {
        return require("ml.service.url");
    }

    public int mlServiceTimeoutSeconds() {
        return Integer.parseInt(get("ml.service.timeout.seconds", "30"));
    }

    public int fetchConcurrency() {
        return Integer.parseInt(get("app.fetch.concurrency", "5"));
    }

    public List<String> stockSymbols() {
        String symbolsFile = get("app.symbols.file", "symbols.txt");
        try (InputStream in = AppConfig.class.getClassLoader().getResourceAsStream(symbolsFile)) {
            if (in == null) {
                throw new IllegalStateException("Symbols file not found on classpath: " + symbolsFile);
            }
            return new String(in.readAllBytes()).lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read symbols file: " + symbolsFile, e);
        }
    }
}
