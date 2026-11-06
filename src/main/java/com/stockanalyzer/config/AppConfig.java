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

    public String growwApiSecret() {
        return require("groww.api.secret");
    }

    /**
     * {@code checksum} (Groww's API key + API secret) or {@code totp} (Groww's TOTP token + TOTP
     * secret).
     */
    public String growwAuthMode() {
        return get("groww.auth.mode", "checksum");
    }

    /**
     * Only the credential the chosen flow needs is required, so these return
     * null rather than throwing; the authenticator factory reports whichever
     * one is actually missing.
     */
    public String growwApiKeyOrNull() {
        return get("groww.api.key", null);
    }

    public String growwApiSecretOrNull() {
        return get("groww.api.secret", null);
    }

    /** Groww's "TOTP token": what its SDK passes as api_key in the TOTP flow. */
    public String growwTotpTokenOrNull() {
        return get("groww.totp.token", null);
    }

    /** Groww's "TOTP secret": the Base32 seed the six-digit code is generated from. */
    public String growwTotpSecretOrNull() {
        return get("groww.totp.secret", null);
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

    /** The least time between a window's entry and its exit: time enough to act on both. */
    public int intradayMinHoldMinutes() {
        return Integer.parseInt(get("intraday.min.hold.minutes", "5"));
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

    /** Where the access token is cached so separate processes can share it. */
    public String tokenCachePath() {
        return get("groww.token.cache.path", "data/.groww-token.json");
    }

    public int rateLimitPerSecond() {
        return Integer.parseInt(get("ingest.rate.limit.per.second", "5"));
    }

    public int rateLimitPerMinute() {
        return Integer.parseInt(get("ingest.rate.limit.per.minute", "120"));
    }

    /** Per-process daily ceiling; 0 disables it. */
    public int rateLimitPerDay() {
        return Integer.parseInt(get("ingest.rate.limit.per.day", "20000"));
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

    /** Comma-separated: console, macos, file. */
    public List<String> alertSinks() {
        return List.of(get("alerts.sinks", "console,macos").split(",")).stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    /** How many symbols the evening step writes down as the Watchlist's shortlist. */
    public int watchlistTop() {
        return Integer.parseInt(get("watchlist.top", "5"));
    }

    /** The least severe alert that becomes a desktop notification: INFO, NOTABLE or URGENT. */
    public String alertDesktopMinSeverity() {
        return get("alerts.desktop.min-severity", "NOTABLE").trim().toUpperCase();
    }

    public String alertFilePath() {
        return get("alerts.file.path", "data/alerts.jsonl");
    }

    /** Percentages above the average buy price at which to say "sell now", one alert each. */
    public List<Double> alertSellTargetsPct() {
        return List.of(get("alerts.sell.targets.pct", "0.6,1.0").split(",")).stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Double::parseDouble)
                .toList();
    }

    /** The smallest price step, in rupees: a sell limit is rounded up to it. NSE equities move in 5 paise. */
    public double alertSellTickSize() {
        return Double.parseDouble(get("alerts.sell.tick.size", "0.05"));
    }

    // The hot-window settings are named for the alerts they once fed; they now tune the baseline
    // entry signal, which still records predictions that are scored the next day.

    public int alertHotWindowMinSessions() {
        return Integer.parseInt(get("alerts.hotwindow.min-sessions", "30"));
    }

    /** Windows starting within this many minutes of the open are ignored by the baseline entry signal. */
    public int alertHotWindowSkipOpeningMinutes() {
        return Integer.parseInt(get("alerts.hotwindow.skip-opening-minutes", "5"));
    }

    public double alertHotWindowMinLowerBound() {
        return Double.parseDouble(get("alerts.hotwindow.min-lcb", "0.10"));
    }

    /**
     * How long after a sell target was last touched an alert is still worth sending. Older than this
     * and the moment has gone, so a restart does not announce the morning.
     */
    public int alertMaxAgeMinutes() {
        return Integer.parseInt(get("alerts.max.age.minutes", "10"));
    }

    // ---- Notes written on the charts ----------------------------------------------------

    /** Where Grafana's own database is, for {@code chartnotes sync}. */
    public String grafanaDbPath() {
        return get("grafana.db.path", "grafana/.runtime/data/grafana.db");
    }

    /** The generic sentiments a note is counted under. */
    public java.util.Set<String> noteSentiments() {
        return csvSet("notes.sentiments", "BULLISH,BEARISH,NEUTRAL");
    }

    /** Home-made labels that stand for a generic sentiment, as {@code UP=BULLISH,...}. */
    public java.util.Map<String, String> noteSentimentAliases() {
        return com.stockanalyzer.note.SentimentVocabulary.parseAliases(get("notes.sentiment.aliases",
                "UP=BULLISH,UPTREND=BULLISH,LONG=BULLISH,DOWN=BEARISH,DOWNTREND=BEARISH,SHORT=BEARISH,"
                        + "LEVEL=NEUTRAL,FLAT=NEUTRAL,SIDEWAYS=NEUTRAL,RANGE=NEUTRAL"));
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

    /** Stage each tick so the dashboards can follow a session in progress. */
    public boolean monitorPersistLiveCandles() {
        return Boolean.parseBoolean(get("monitor.persist.live.candles", "true"));
    }

    /** How often the daemon pulls the day's fills from the broker; zero turns it off. */
    public int tradesLiveImportIntervalMinutes() {
        return Integer.parseInt(get("trades.live.import.interval.minutes", "5"));
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

    // ---- CAN SLIM ---------------------------------------------------------------------

    /** The fundamental filter screener.in runs; see application.properties for the default. */
    public String canSlimQuery() {
        return get("canslim.screener.query", null);
    }

    /** The browser's screener.in {@code sessionid} cookie. Prefer the SCREENER_SESSION_ID env var. */
    public String screenerSessionIdOrNull() {
        return get("screener.session.id", null);
    }

    public String screenerBaseUrl() {
        return get("screener.base.url", "https://www.screener.in");
    }

    /** Between two company-page reads. screener.in refuses a faster pace than about one every ten seconds. */
    public long screenerRequestIntervalMillis() {
        return Long.parseLong(get("canslim.screener.request.interval.ms", "12000"));
    }

    /** How old a company's profile may be before its screener.in page is read again. */
    public int companyProfileRefreshDays() {
        return Integer.parseInt(get("company.profile.refresh.days", "7"));
    }

    /** The most company pages one nightly run reads for profiles; zero reads every one that is due. */
    public int companyProfileMaxPagesPerRun() {
        return Integer.parseInt(get("company.profile.max.pages.per.run", "12"));
    }

    /** How long to wait after screener.in answers 429, unless it says how long itself. */
    public long screenerRateLimitBackoffMillis() {
        return Long.parseLong(get("canslim.screener.rate.limit.backoff.ms", "60000"));
    }

    /** The most company pages one scan reads. */
    public int canSlimFundamentalsMaxPagesPerRun() {
        return Integer.parseInt(get("canslim.fundamentals.max.pages.per.run", "60"));
    }

    /** An index ETF standing in for the market: the candle feed prices tradeable symbols. */
    public String canSlimIndexSymbol() {
        return get("canslim.market.index.symbol", "NIFTYBEES");
    }

    public int canSlimHistoryDays() {
        return Integer.parseInt(get("canslim.history.days", "400"));
    }

    public int canSlimMaxCandidates() {
        return Integer.parseInt(get("canslim.max.candidates", "150"));
    }

    /** Whether the configured symbols join each scan - for a wider RS field and their sell signals. */
    public boolean canSlimIncludeUniverse() {
        return Boolean.parseBoolean(get("canslim.include.universe", "true"));
    }

    /**
     * The slow pace for company pages with nothing new to learn from (an ETF, a company past the
     * reporting deadline still missing the quarter). Otherwise the calendar decides: see
     * {@code FundamentalsRefreshPolicy}.
     */
    public int canSlimFundamentalsCacheDays() {
        return Integer.parseInt(get("canslim.fundamentals.cache.days", "7"));
    }

    /** In reporting season, how often to look again at a company that has not yet shown the new quarter. */
    public int canSlimFundamentalsSeasonCheckDays() {
        return Integer.parseInt(get("canslim.fundamentals.season.check.days", "2"));
    }

    // ---- CAN SLIM, whole market ----------------------------------------------------

    /** Whether the nightly scan also prices and ranks every listed NSE/BSE equity. */
    public boolean canSlimMarketEnabled() {
        return Boolean.parseBoolean(get("canslim.market.enabled", "false"));
    }

    public String canSlimCatalogUrl() {
        return get("canslim.market.catalog.url", "https://growwapi-assets.groww.in/instruments/instrument.csv");
    }

    public String canSlimCatalogCache() {
        return get("canslim.market.catalog.cache", "data/instruments.csv");
    }

    public int canSlimCatalogMaxAgeHours() {
        return Integer.parseInt(get("canslim.market.catalog.max.age.hours", "24"));
    }

    public java.util.Set<String> canSlimMarketExchanges() {
        return csvSet("canslim.market.exchanges", "NSE,BSE");
    }

    /** NSE series to cover; the SME ones are added by {@code canslim.market.include.sme}. */
    public java.util.Set<String> canSlimMarketNseSeries() {
        java.util.Set<String> series = csvSet("canslim.market.nse.series", "EQ,BE,BZ");
        if (canSlimMarketIncludeSme()) {
            series.addAll(csvSet("canslim.market.nse.sme.series", "SM,ST,SZ"));
        }
        return series;
    }

    /** BSE groups to cover, for the companies NSE does not list. */
    public java.util.Set<String> canSlimMarketBseSeries() {
        java.util.Set<String> series = csvSet("canslim.market.bse.series", "A,B,T,Z,P,ZP,TS");
        if (canSlimMarketIncludeSme()) {
            series.addAll(csvSet("canslim.market.bse.sme.series", "X,XT,M,MT,MS"));
        }
        return series;
    }

    /** Trading symbols never priced in a market scan; the catalog spells them in capitals. */
    public java.util.Set<String> canSlimMarketExcluded() {
        return csvSet("canslim.market.exclude", "").stream()
                .map(symbol -> symbol.toUpperCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /** Days a listing Groww refused (unknown symbol, no bars) is left unasked; 0 asks about it every scan. */
    public int canSlimMarketRejectDays() {
        return Integer.parseInt(get("canslim.market.reject.days", "30"));
    }

    public boolean canSlimMarketIncludeSme() {
        return Boolean.parseBoolean(get("canslim.market.include.sme", "false"));
    }

    public int canSlimMarketMinSessions() {
        return Integer.parseInt(get("canslim.market.min.sessions", "126"));
    }

    public double canSlimMarketMinPrice() {
        return Double.parseDouble(get("canslim.market.min.price", "10"));
    }

    /** Average daily value traded, in rupees, below which a listing is not ranked. */
    public double canSlimMarketMinTurnover() {
        return Double.parseDouble(get("canslim.market.min.turnover", "10000000"));
    }

    public int canSlimMarketMaxStaleDays() {
        return Integer.parseInt(get("canslim.market.max.stale.days", "10"));
    }

    /** The RS rating from which a market-wide listing has its company page read. */
    public int canSlimMarketFundamentalsMinRs() {
        return Integer.parseInt(get("canslim.market.fundamentals.min.rs",
                String.valueOf((int) canSlim("min.rs.rating", 80))));
    }

    /** A market listing is selected only with a market cap above this, in rupees crore. */
    public double canSlimMarketMinMarketCapCr() {
        return Double.parseDouble(get("canslim.market.min.market.cap.cr", "1000"));
    }

    public int canSlimMarketKeepRuns() {
        return Integer.parseInt(get("canslim.market.keep.runs", "30"));
    }

    /** How many of the strongest stocks by RS the report lists. */
    public int canSlimMarketTop() {
        return Integer.parseInt(get("canslim.market.report.top", "50"));
    }

    private java.util.Set<String> csvSet(String key, String fallback) {
        return java.util.Arrays.stream(get(key, fallback).split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    public double canSlim(String key, double fallback) {
        return Double.parseDouble(get("canslim." + key, String.valueOf(fallback)));
    }
}
