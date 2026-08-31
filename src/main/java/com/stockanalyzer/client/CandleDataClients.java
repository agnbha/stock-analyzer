package com.stockanalyzer.client;

import com.stockanalyzer.auth.GrowwAuthenticator;

import java.net.http.HttpClient;

/**
 * Builds the candle client every entry point should use.
 *
 * <p>Assembling this by hand is how an entry point ends up talking to the API
 * with no limiter at all, so there is one factory and no reason to construct
 * {@link GrowwCandleDataClient} directly outside it.
 *
 * <p>Order matters. Chunking is outermost, so a retry repeats only the one
 * chunk that failed rather than the whole range. The limiter is innermost, so
 * every real HTTP request passes through it exactly once - including retries,
 * which is the case a limiter placed further out would miss.
 */
public final class CandleDataClients {

    private CandleDataClients() {
    }

    public static CandleDataClient rateLimited(HttpClient httpClient,
                                               GrowwAuthenticator authenticator,
                                               String baseUrl,
                                               RateLimiter rateLimiter,
                                               int maxRetries,
                                               long retryBackoffMillis,
                                               int maxDaysPerRequest) {
        CandleDataClient raw = new GrowwCandleDataClient(httpClient, authenticator, baseUrl);
        return new ChunkedCandleDataClient(
                new RetryingCandleDataClient(
                        new RateLimitedCandleDataClient(raw, rateLimiter),
                        maxRetries, retryBackoffMillis),
                new CandleRangeChunker(maxDaysPerRequest));
    }
}
