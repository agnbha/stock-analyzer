package com.stockanalyzer.client;

import com.stockanalyzer.auth.GrowwAuthenticator;
import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.StockCandleSeries;
import com.stockanalyzer.util.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * {@link CandleDataClient} backed by Groww's historical candle REST endpoint:
 * {@code GET /v1/historical/candle/range}.
 *
 * <p>Reference: https://groww.in/trade-api/docs/curl/historical-data
 */
public final class GrowwCandleDataClient implements CandleDataClient {

    private static final Logger log = LoggerFactory.getLogger(GrowwCandleDataClient.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** A server asking us to wait an hour is more likely a bad header than a real instruction. */
    private static final long MAX_RETRY_AFTER_MILLIS = 5 * 60 * 1000L;

    private final HttpClient httpClient;
    private final GrowwAuthenticator authenticator;
    private final String baseUrl;

    public GrowwCandleDataClient(HttpClient httpClient, GrowwAuthenticator authenticator, String baseUrl) {
        this.httpClient = httpClient;
        this.authenticator = authenticator;
        this.baseUrl = baseUrl;
    }

    @Override
    public StockCandleSeries fetchCandles(String symbol, String exchange, String segment,
                                           LocalDateTime startTime, LocalDateTime endTime, int intervalMinutes) {
        String url = baseUrl + "/historical/candle/range"
                + "?exchange=" + encode(exchange)
                + "&segment=" + encode(segment)
                + "&trading_symbol=" + encode(symbol)
                + "&start_time=" + encode(startTime.format(TIME_FORMAT))
                + "&end_time=" + encode(endTime.format(TIME_FORMAT))
                + "&interval_in_minutes=" + intervalMinutes;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + authenticator.getAccessToken())
                .header("X-API-VERSION", "1.0")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new GrowwApiException("Failed to reach Groww candle endpoint for " + symbol, e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new GrowwApiException(
                    "Groww candle request for " + symbol + " failed with status " + response.statusCode()
                            + ": " + response.body(),
                    response.statusCode(),
                    retryAfterMillis(response));
        }

        GrowwCandleResponse candleResponse;
        try {
            candleResponse = JsonMapper.INSTANCE.readValue(response.body(), GrowwCandleResponse.class);
        } catch (Exception e) {
            throw new GrowwApiException("Failed to parse Groww candle response for " + symbol + ": " + response.body(), e);
        }

        if (!"SUCCESS".equalsIgnoreCase(candleResponse.status()) || candleResponse.payload() == null) {
            throw new GrowwApiException("Groww API returned non-success status for " + symbol + ": " + response.body());
        }

        List<Candle> candles = candleResponse.payload().candles().stream()
                .map(GrowwCandleDataClient::toCandle)
                .toList();

        log.debug("Fetched {} candles for {}", candles.size(), symbol);
        return new StockCandleSeries(symbol, exchange, segment, candles);
    }

    /**
     * How long the server told us to wait. {@code Retry-After} is seconds by
     * the HTTP spec; some gateways send milliseconds or an HTTP date instead,
     * so anything unparseable is treated as "did not say" rather than guessed
     * at.
     */
    private static long retryAfterMillis(HttpResponse<String> response) {
        for (String header : List.of("Retry-After", "retry-after", "X-RateLimit-Reset-After")) {
            String value = response.headers().firstValue(header).orElse(null);
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                double seconds = Double.parseDouble(value.trim());
                if (seconds > 0) {
                    return Math.min((long) (seconds * 1000), MAX_RETRY_AFTER_MILLIS);
                }
            } catch (NumberFormatException e) {
                log.debug("Unparseable {} header: {}", header, value);
            }
        }
        return 0;
    }

    private static Candle toCandle(List<Double> row) {
        if (row.size() < 6) {
            throw new GrowwApiException("Unexpected candle row shape: " + row);
        }
        return new Candle(
                row.get(0).longValue(),
                row.get(1),
                row.get(2),
                row.get(3),
                row.get(4),
                row.get(5).longValue());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
