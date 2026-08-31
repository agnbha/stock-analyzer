package com.stockanalyzer;

import com.stockanalyzer.auth.ChecksumGrowwAuthenticator;
import com.stockanalyzer.auth.GrowwAuthenticator;
import com.stockanalyzer.client.CandleDataClient;
import com.stockanalyzer.client.FundamentalsClient;
import com.stockanalyzer.client.CandleDataClients;
import com.stockanalyzer.client.TokenBucketRateLimiter;
import com.stockanalyzer.client.HistoricalFundamentalsClient;
import com.stockanalyzer.config.AppConfig;
import com.stockanalyzer.model.FundamentalsOutcome;
import com.stockanalyzer.report.FundamentalsCsvWriter;
import com.stockanalyzer.service.FundamentalsExportService;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Composition root: fetches fundamentals for every configured symbol and
 * writes them to a CSV file. Defaults to deriving fundamentals from
 * historical candles ({@link HistoricalFundamentalsClient}) since that only
 * needs the historical data access every Groww API key has; see the wiring
 * below for how to switch to the richer live-quote source once available.
 *
 * <p>Usage: {@code java -cp stock-analyzer.jar com.stockanalyzer.FundamentalsExportMain [output.csv]}
 */
public final class FundamentalsExportMain {

    public static void main(String[] args) {
        AppConfig config = AppConfig.load();
        Path outputPath = Path.of(args.length > 0 ? args[0] : "fundamentals.csv");

        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        GrowwAuthenticator authenticator = new ChecksumGrowwAuthenticator(
                httpClient, config.growwBaseUrl(), config.growwApiKey(), config.growwApiSecret());

        // Sourced from historical candles (GET /v1/historical/candle/range), which needs only
        // the standard historical data access every Groww API key has. Swap in
        // `new GrowwFundamentalsClient(httpClient, authenticator, config.growwBaseUrl())` instead
        // once "Live Data" is enabled on this key at groww.in/trade-api/api-keys — that gives
        // richer fields (market_cap, circuit limits) via GET /v1/live-data/quote, which currently
        // 403s ("Access forbidden for this request") on this account.
        CandleDataClient candleDataClient = CandleDataClients.rateLimited(httpClient, authenticator,
                config.growwBaseUrl(),
                new TokenBucketRateLimiter(config.rateLimitPerSecond(), config.rateLimitPerMinute(),
                        config.rateLimitPerDay()),
                config.ingestMaxRetries(), config.ingestRetryBackoffMillis(),
                config.backfillMaxDaysPerRequest());
        FundamentalsClient fundamentalsClient = new HistoricalFundamentalsClient(candleDataClient, config.fundamentalsLookbackDays());

        ExecutorService executor = Executors.newFixedThreadPool(config.fetchConcurrency());
        try {
            FundamentalsExportService service = new FundamentalsExportService(fundamentalsClient, executor);

            List<String> symbols = config.stockSymbols();
            System.out.printf("Fetching fundamentals for %d symbols on %s/%s%n",
                    symbols.size(), config.exchange(), config.segment());

            List<FundamentalsOutcome> outcomes = service.fetchAll(symbols, config.exchange(), config.segment());

            new FundamentalsCsvWriter().write(outcomes, outputPath);

            long successCount = outcomes.stream().filter(FundamentalsOutcome::isSuccess).count();
            System.out.printf("Wrote %d/%d symbols to %s (%d failed).%n",
                    successCount, outcomes.size(), outputPath.toAbsolutePath(), outcomes.size() - successCount);
        } finally {
            executor.shutdown();
        }
    }
}
