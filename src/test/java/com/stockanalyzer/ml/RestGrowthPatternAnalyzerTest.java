package com.stockanalyzer.ml;

import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.GrowthTrend;
import com.stockanalyzer.model.StockCandleSeries;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestGrowthPatternAnalyzerTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postsSeriesAndMapsPrediction() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/predict", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"symbol\":\"ABC\",\"trend\":\"BEARISH\",\"growthScore\":-0.4,\"confidence\":0.8}");
        });
        server.start();

        RestGrowthPatternAnalyzer analyzer = new RestGrowthPatternAnalyzer(
                HttpClient.newHttpClient(), baseUrl() + "/predict", Duration.ofSeconds(2));
        var result = analyzer.analyze(series());

        assertEquals("ABC", result.symbol());
        assertEquals(GrowthTrend.BEARISH, result.trend());
        assertEquals(-0.4, result.growthScore());
        assertEquals(0.8, result.confidence());
        assertTrue(requestBody.get().contains("\"symbol\":\"ABC\""));
        assertTrue(requestBody.get().contains("\"candles\""));
    }

    @Test
    void reportsHttpFailureAsMlServiceException() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/predict", exchange -> respond(exchange, 503, "unavailable"));
        server.start();

        RestGrowthPatternAnalyzer analyzer = new RestGrowthPatternAnalyzer(
                HttpClient.newHttpClient(), baseUrl() + "/predict", Duration.ofSeconds(2));

        MlServiceException exception = assertThrows(MlServiceException.class, () -> analyzer.analyze(series()));
        assertTrue(exception.getMessage().contains("status 503"));
    }

    @Test
    void reportsMalformedPredictionAsMlServiceException() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/predict", exchange -> respond(exchange, 200, "not-json"));
        server.start();

        RestGrowthPatternAnalyzer analyzer = new RestGrowthPatternAnalyzer(
                HttpClient.newHttpClient(), baseUrl() + "/predict", Duration.ofSeconds(2));

        assertThrows(MlServiceException.class, () -> analyzer.analyze(series()));
    }

    private StockCandleSeries series() {
        return new StockCandleSeries("ABC", "NSE", "CASH", List.of(new Candle(1, 10, 12, 9, 11, 100)));
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
