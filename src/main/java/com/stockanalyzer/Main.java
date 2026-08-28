package com.stockanalyzer;

import com.stockanalyzer.auth.ChecksumGrowwAuthenticator;
import com.stockanalyzer.auth.GrowwAuthenticator;
import com.stockanalyzer.client.CandleDataClient;
import com.stockanalyzer.client.GrowwCandleDataClient;
import com.stockanalyzer.config.AppConfig;
import com.stockanalyzer.ml.GrowthPatternAnalyzer;
import com.stockanalyzer.ml.RestGrowthPatternAnalyzer;
import com.stockanalyzer.model.StockAnalysisOutcome;
import com.stockanalyzer.report.AnalysisReporter;
import com.stockanalyzer.report.ConsoleAnalysisReporter;
import com.stockanalyzer.service.StockGrowthAnalysisService;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Composition root: wires concrete implementations to their abstractions and
 * runs one batch analysis pass over the configured symbol list. All wiring
 * lives here so every other class depends only on interfaces (DIP).
 */
public final class Main {

    public static void main(String[] args) {
        AppConfig config = AppConfig.load();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        GrowwAuthenticator authenticator = new ChecksumGrowwAuthenticator(
                httpClient, config.growwBaseUrl(), config.growwApiKey(), config.growwApiSecret());

        CandleDataClient candleDataClient = new GrowwCandleDataClient(httpClient, authenticator, config.growwBaseUrl());

        GrowthPatternAnalyzer growthPatternAnalyzer = new RestGrowthPatternAnalyzer(
                httpClient, config.mlServiceUrl(), Duration.ofSeconds(config.mlServiceTimeoutSeconds()));

        ExecutorService executor = Executors.newFixedThreadPool(config.fetchConcurrency());
        AnalysisReporter reporter = new ConsoleAnalysisReporter();

        try {
            StockGrowthAnalysisService service = new StockGrowthAnalysisService(candleDataClient, growthPatternAnalyzer, executor);

            List<String> symbols = config.stockSymbols();
            LocalDateTime endTime = LocalDateTime.now();
            LocalDateTime startTime = endTime.minusDays(config.lookbackDays());

            System.out.printf("Analyzing %d symbols on %s/%s from %s to %s (interval=%d min)%n%n",
                    symbols.size(), config.exchange(), config.segment(), startTime, endTime, config.candleIntervalMinutes());

            List<StockAnalysisOutcome> outcomes = service.analyzeSymbols(
                    symbols, config.exchange(), config.segment(), startTime, endTime, config.candleIntervalMinutes());

            reporter.report(outcomes);
        } finally {
            executor.shutdown();
        }
    }
}
