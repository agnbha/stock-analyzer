package com.stockanalyzer.service;

import com.stockanalyzer.client.FundamentalsClient;
import com.stockanalyzer.model.FundamentalsOutcome;
import com.stockanalyzer.model.StockFundamentals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Fetches fundamentals for a batch of symbols concurrently. Depends only on
 * the {@link FundamentalsClient} abstraction so it can be unit tested with a
 * fake and is unaffected by swapping the data provider.
 */
public final class FundamentalsExportService {

    private static final Logger log = LoggerFactory.getLogger(FundamentalsExportService.class);

    private final FundamentalsClient fundamentalsClient;
    private final ExecutorService executor;

    public FundamentalsExportService(FundamentalsClient fundamentalsClient, ExecutorService executor) {
        this.fundamentalsClient = fundamentalsClient;
        this.executor = executor;
    }

    public List<FundamentalsOutcome> fetchAll(List<String> symbols, String exchange, String segment) {
        List<CompletableFuture<FundamentalsOutcome>> futures = symbols.stream()
                .map(symbol -> CompletableFuture.supplyAsync(() -> fetchOne(symbol, exchange, segment), executor))
                .toList();

        return futures.stream().map(CompletableFuture::join).toList();
    }

    private FundamentalsOutcome fetchOne(String symbol, String exchange, String segment) {
        try {
            StockFundamentals fundamentals = fundamentalsClient.fetchFundamentals(symbol, exchange, segment);
            return FundamentalsOutcome.success(fundamentals);
        } catch (Exception e) {
            log.warn("Failed to fetch fundamentals for {}: {}", symbol, e.getMessage());
            return FundamentalsOutcome.failure(symbol, e.getMessage());
        }
    }
}
