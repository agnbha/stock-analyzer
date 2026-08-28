package com.stockanalyzer.client;

import com.stockanalyzer.auth.GrowwAuthenticator;
import com.stockanalyzer.model.StockFundamentals;
import com.stockanalyzer.util.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * {@link FundamentalsClient} backed by Groww's live quote REST endpoint:
 * {@code GET /v1/live-data/quote}.
 *
 * <p><b>Requires "Live Data" to be enabled on the Groww API key</b> (a
 * separate entitlement from historical candle access, toggled at
 * groww.in/trade-api/api-keys). Without it every request fails with
 * {@code 403 Access forbidden for this request}, regardless of the
 * key/secret being otherwise valid. If your key only has historical data
 * access, use {@link HistoricalFundamentalsClient} instead.
 *
 * <p>Reference: https://groww.in/trade-api/docs/curl/live-data
 */
public final class GrowwFundamentalsClient implements FundamentalsClient {

    private static final Logger log = LoggerFactory.getLogger(GrowwFundamentalsClient.class);

    private final HttpClient httpClient;
    private final GrowwAuthenticator authenticator;
    private final String baseUrl;

    public GrowwFundamentalsClient(HttpClient httpClient, GrowwAuthenticator authenticator, String baseUrl) {
        this.httpClient = httpClient;
        this.authenticator = authenticator;
        this.baseUrl = baseUrl;
    }

    @Override
    public StockFundamentals fetchFundamentals(String symbol, String exchange, String segment) {
        String url = baseUrl + "/live-data/quote"
                + "?exchange=" + encode(exchange)
                + "&segment=" + encode(segment)
                + "&trading_symbol=" + encode(symbol);

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
            throw new GrowwApiException("Failed to reach Groww quote endpoint for " + symbol, e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new GrowwApiException(
                    "Groww quote request for " + symbol + " failed with status " + response.statusCode()
                            + ": " + response.body());
        }

        GrowwQuoteResponse quoteResponse;
        try {
            quoteResponse = JsonMapper.INSTANCE.readValue(response.body(), GrowwQuoteResponse.class);
        } catch (Exception e) {
            throw new GrowwApiException("Failed to parse Groww quote response for " + symbol + ": " + response.body(), e);
        }

        if (!"SUCCESS".equalsIgnoreCase(quoteResponse.status()) || quoteResponse.payload() == null) {
            throw new GrowwApiException("Groww API returned non-success status for " + symbol + ": " + response.body());
        }

        GrowwQuoteResponse.Payload payload = quoteResponse.payload();
        GrowwQuoteResponse.Ohlc ohlc = payload.ohlc();

        log.debug("Fetched fundamentals for {}", symbol);
        return new StockFundamentals(
                symbol,
                exchange,
                segment,
                payload.lastPrice(),
                ohlc != null ? ohlc.open() : null,
                ohlc != null ? ohlc.high() : null,
                ohlc != null ? ohlc.low() : null,
                ohlc != null ? ohlc.close() : null,
                payload.dayChange(),
                payload.dayChangePerc(),
                payload.volume(),
                payload.marketCap(),
                payload.averagePrice(),
                payload.week52High(),
                payload.week52Low(),
                payload.upperCircuitLimit(),
                payload.lowerCircuitLimit());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
