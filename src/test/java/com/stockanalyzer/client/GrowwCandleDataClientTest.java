package com.stockanalyzer.client;

import com.stockanalyzer.auth.GrowwAuthenticator;
import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.StockCandleSeries;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrowwCandleDataClientTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 6, 1, 9, 15);

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void mapsSuccessfulResponseAndBuildsAuthenticatedRequest() throws IOException {
        AtomicReference<String> requestUri = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/historical/candle/range", exchange -> {
            requestUri.set(exchange.getRequestURI().toString());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"status\":\"SUCCESS\",\"payload\":{\"candles\":[[1700000000,100.5,110.0,99.0,108.0,2500]],\"start_time\":\"start\",\"end_time\":\"end\",\"interval_in_minutes\":1440}}");
        });
        server.start();

        GrowwCandleDataClient client = new GrowwCandleDataClient(
                HttpClient.newHttpClient(), () -> "token-123", baseUrl());
        StockCandleSeries result = client.fetchCandles("ABC & CO", "NSE", "CASH",
                LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 2, 0, 0), 1440);

        assertEquals("ABC & CO", result.symbol());
        assertEquals(1, result.candles().size());
        assertEquals(108.0, result.candles().getFirst().close());
        assertEquals("Bearer token-123", authorization.get());
        assertTrue(requestUri.get().contains("trading_symbol=ABC+%26+CO"));
        assertTrue(requestUri.get().contains("interval_in_minutes=1440"));
    }

    @Test
    void rejectsNonSuccessApiResponse() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/historical/candle/range", exchange -> respond(exchange, 429, "rate limited"));
        server.start();

        GrowwCandleDataClient client = new GrowwCandleDataClient(
                HttpClient.newHttpClient(), () -> "token", baseUrl());

        GrowwApiException exception = assertThrows(GrowwApiException.class, () -> client.fetchCandles(
                "ABC", "NSE", "CASH", LocalDateTime.now(), LocalDateTime.now(), 5));
        assertTrue(exception.getMessage().contains("status 429"));
    }

    @Test
    @DisplayName("a malformed row is dropped, and the rest of the response still lands")
    void dropsMalformedCandleRowsWithoutFailing() throws IOException {
        // This used to throw, which meant one bad row in three months of data
        // cost the entire backfill for that symbol. Dropping it is the useful
        // behaviour; the count is logged, and a large proportion warns.
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/historical/candle/range", exchange -> respond(exchange, 200,
                "{\"status\":\"SUCCESS\",\"payload\":{\"candles\":"
                        + "[[1,2,3],[1785123960,2274.0,2276.0,2273.0,2275.0,1200]]}}"));
        server.start();

        GrowwCandleDataClient client = new GrowwCandleDataClient(
                HttpClient.newHttpClient(), (GrowwAuthenticator) () -> "token", baseUrl());

        List<Candle> candles = client.fetchCandles("ABC", "NSE", "CASH",
                LocalDateTime.now(), LocalDateTime.now(), 5).candles();

        assertEquals(1, candles.size());
        assertEquals(1785123960L, candles.getFirst().epochSeconds());
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] response = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    @Test
    @DisplayName("a missing volume becomes zero rather than losing the candle")
    void nullVolumeBecomesZero() throws IOException {
        // Real shape from the feed: a minute with no trades reports no volume.
        server = respondWith("""
                {"status":"SUCCESS","payload":{"candles":[
                  [1786441500,2440.3,2442.1,2440.3,2442.1,null]
                ]}}""");

        List<Candle> candles = client().fetchCandles("TCS", "NSE", "CASH",
                START, START.plusDays(1), 1).candles();

        assertEquals(1, candles.size());
        assertEquals(0L, candles.getFirst().volume());
        assertEquals(2442.1, candles.getFirst().close(), 0.0001);
    }

    @Test
    @DisplayName("a row with a missing price is dropped, not guessed at")
    void nullPriceIsDropped() throws IOException {
        // A null open cannot be invented without corrupting what the detector measures.
        server = respondWith("""
                {"status":"SUCCESS","payload":{"candles":[
                  [1785123900,null,2285.3,2272.8,2274.0,66947],
                  [1785123960,2274.0,2276.0,2273.0,2275.0,1200]
                ]}}""");

        List<Candle> candles = client().fetchCandles("TCS", "NSE", "CASH",
                START, START.plusDays(1), 1).candles();

        assertEquals(1, candles.size(), "the usable row survives");
        assertEquals(1785123960L, candles.getFirst().epochSeconds());
    }

    @Test
    @DisplayName("one bad row no longer costs the whole request")
    void oneBadRowDoesNotFailTheFetch() throws IOException {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            rows.append(i == 250
                    ? "[1785120000,null,2285.3,2272.8,2274.0,66947],"
                    : "[" + (1785120000L + i * 60) + ",2274.0,2276.0,2273.0,2275.0,1200],");
        }
        rows.setLength(rows.length() - 1);
        server = respondWith("{\"status\":\"SUCCESS\",\"payload\":{\"candles\":[" + rows + "]}}");

        List<Candle> candles = client().fetchCandles("TCS", "NSE", "CASH",
                START, START.plusDays(1), 1).candles();

        assertEquals(499, candles.size(), "499 good rows survive one bad one");
    }

    @Test
    @DisplayName("a payload with no candles array is an empty result, not a crash")
    void missingCandlesArrayIsEmpty() throws IOException {
        server = respondWith("{\"status\":\"SUCCESS\",\"payload\":{}}");

        assertTrue(client().fetchCandles("TCS", "NSE", "CASH", START, START.plusDays(1), 1)
                .candles().isEmpty());
    }

    @Test
    @DisplayName("a short row is dropped rather than failing the request")
    void shortRowIsDropped() throws IOException {
        server = respondWith("""
                {"status":"SUCCESS","payload":{"candles":[
                  [1785123900,2274.0,2276.0],
                  [1785123960,2274.0,2276.0,2273.0,2275.0,1200]
                ]}}""");

        assertEquals(1, client().fetchCandles("TCS", "NSE", "CASH", START, START.plusDays(1), 1)
                .candles().size());
    }

    private GrowwCandleDataClient client() {
        return new GrowwCandleDataClient(HttpClient.newHttpClient(),
                () -> "token", "http://localhost:" + server.getAddress().getPort());
    }

    private static HttpServer respondWith(String body) throws IOException {
        HttpServer created = HttpServer.create(new InetSocketAddress(0), 0);
        created.createContext("/historical/candle/range", exchange -> {
            byte[] payload = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (var out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        created.start();
        return created;
    }
}
