package com.stockanalyzer.client;

import com.stockanalyzer.auth.GrowwAuthenticator;
import com.stockanalyzer.model.StockCandleSeries;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrowwCandleDataClientTest {

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
    void rejectsMalformedCandleRows() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/historical/candle/range", exchange -> respond(exchange, 200,
                "{\"status\":\"SUCCESS\",\"payload\":{\"candles\":[[1,2,3]]}}"));
        server.start();

        GrowwCandleDataClient client = new GrowwCandleDataClient(
                HttpClient.newHttpClient(), (GrowwAuthenticator) () -> "token", baseUrl());

        assertThrows(GrowwApiException.class, () -> client.fetchCandles(
                "ABC", "NSE", "CASH", LocalDateTime.now(), LocalDateTime.now(), 5));
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
}
