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

    // ---- Part 1: intraday analysis and the local store -------------------------------

    /** Candle size used for intraday analysis; 1 = one-minute candles. */
    public int intradayIntervalMinutes() {
        return Integer.parseInt(get("intraday.candle.interval.minutes", "1"));
    }

    public int intradayTopN() {
        return Integer.parseInt(get("intraday.top.n", "3"));
    }

    /** HIGH_LOW measures entry-low to exit-high; CLOSE_CLOSE is the conservative reading. */
    public String intradayPriceBasis() {
        return get("intraday.price.basis", "HIGH_LOW");
    }

    public int intradayMinHoldCandles() {
        return Integer.parseInt(get("intraday.min.hold.candles", "1"));
    }

    public double intradayMinGainPct() {
        return Double.parseDouble(get("intraday.min.gain.pct", "0.0"));
    }

    public boolean intradayStoreRawCandles() {
        return Boolean.parseBoolean(get("intraday.store.raw.candles", "true"));
    }

    public String databaseUrl() {
        return get("db.url", "jdbc:sqlite:data/stock-analyzer.db");
    }

    public int databaseBusyTimeoutMillis() {
        return Integer.parseInt(get("db.busy.timeout.ms", "5000"));
    }

    public int rateLimitPerSecond() {
        return Integer.parseInt(get("ingest.rate.limit.per.second", "15"));
    }

    public int rateLimitPerMinute() {
        return Integer.parseInt(get("ingest.rate.limit.per.minute", "400"));
    }

    public int ingestMaxRetries() {
        return Integer.parseInt(get("ingest.max.retries", "4"));
    }

    public long ingestRetryBackoffMillis() {
        return Long.parseLong(get("ingest.retry.backoff.ms", "500"));
    }

    public int backfillMaxDaysPerRequest() {
        return Integer.parseInt(get("backfill.max.days.per.request", "5"));
    }

    // ---- Part 2: the time-of-day prior and the model --------------------------------

    /** False keeps the statistical baseline in charge until a model earns its place. */
    public boolean modelEnabled() {
        return Boolean.parseBoolean(get("model.enabled", "false"));
    }

    public String modelServiceUrl() {
        return get("model.service.url", "http://localhost:8001/predict/intraday");
    }

    public int modelHorizonMinutes() {
        return Integer.parseInt(get("model.horizon.minutes", "30"));
    }

    public int modelTimeoutSeconds() {
        return Integer.parseInt(get("model.timeout.seconds", "20"));
    }

    public int hotWindowBucketMinutes() {
        return Integer.parseInt(get("hotwindow.bucket.minutes", "5"));
    }

    public int hotWindowLookbackDays() {
        return Integer.parseInt(get("hotwindow.lookback.days", "60"));
    }

    // ---- Part 3: alerts --------------------------------------------------------------

    public boolean alertsEnabled() {
        return Boolean.parseBoolean(get("alerts.enabled", "true"));
    }

    /** Comma-separated: console, macos, file. */
    public List<String> alertSinks() {
        return List.of(get("alerts.sinks", "console").split(",")).stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    public String alertFilePath() {
        return get("alerts.file.path", "data/alerts.jsonl");
    }

    public int alertLeadTimeMinutes() {
        return Integer.parseInt(get("alerts.lead.time.minutes", "2"));
    }

    public int alertHotWindowMinSessions() {
        return Integer.parseInt(get("alerts.hotwindow.min-sessions", "30"));
    }

    public double alertHotWindowMinLowerBound() {
        return Double.parseDouble(get("alerts.hotwindow.min-lcb", "0.10"));
    }

    public double alertModelMinProbability() {
        return Double.parseDouble(get("alerts.model.min-probability", "0.65"));
    }

    public int alertCooldownMinutes() {
        return Integer.parseInt(get("alerts.cooldown.minutes", "15"));
    }

    public int alertMaxPerSymbolPerDay() {
        return Integer.parseInt(get("alerts.max.per.symbol.per.day", "3"));
    }

    public int alertMaxPerDay() {
        return Integer.parseInt(get("alerts.max.per.day", "30"));
    }

    public String holidaysFile() {
        return get("alerts.holidays.file", "nse-holidays-2026.txt");
    }

    // ---- Part 4: live monitoring -----------------------------------------------------

    public int monitorPollIntervalSeconds() {
        return Integer.parseInt(get("monitor.poll.interval.seconds", "150"));
    }

    public String monitorSessionOpen() {
        return get("monitor.session.open", "09:15");
    }

    public String monitorSessionClose() {
        return get("monitor.session.close", "15:30");
    }

    public String monitorTimezone() {
        return get("monitor.timezone", "Asia/Kolkata");
    }

    public int monitorPostCloseGraceSeconds() {
        return Integer.parseInt(get("monitor.post.close.grace.seconds", "120"));
    }

    public boolean monitorAnsi() {
        return Boolean.parseBoolean(get("monitor.view.ansi", "true"));
    }

    // ---- Part 5: the trade journal ---------------------------------------------------

    /** broker | csv | manual. */
    public String tradesSource() {
        return get("trades.source", "broker");
    }

    public String tradesCsvDateFormat() {
        return get("trades.csv.date.format", "yyyy-MM-dd HH:mm:ss");
    }

    public String tradesDefaultProduct() {
        return get("trades.default.product", "MIS");
    }

    public boolean preferBrokerCharges() {
        return Boolean.parseBoolean(get("charges.prefer.broker.actuals", "true"));
    }

    /** How long before a fill an event or alert still counts as its reason. */
    public int reasonLookbackMinutes() {
        return Integer.parseInt(get("trades.reason.lookback.minutes", "15"));
    }

    public int financialYearStartMonth() {
        return Integer.parseInt(get("report.fy.start.month", "4"));
    }

    public double chargeRate(String key, double fallback) {
        return Double.parseDouble(get("charges." + key, String.valueOf(fallback)));
    }
}
