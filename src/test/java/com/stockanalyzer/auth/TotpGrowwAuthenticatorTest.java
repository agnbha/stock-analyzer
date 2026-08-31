package com.stockanalyzer.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TotpGrowwAuthenticatorTest {

    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("exchanges the API key and a current code for a token, then caches it")
    void sendsTotpAndCachesTheToken() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> lastBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/token/api/access", exchange -> {
            requests.incrementAndGet();
            assertEquals("Bearer api-key", exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"token\":\"totp-token\",\"expiry\":\"2099-01-01T00:00:00\"}");
        });
        server.start();

        TotpGrowwAuthenticator authenticator = new TotpGrowwAuthenticator(
                HttpClient.newHttpClient(), baseUrl(), "api-key", SECRET);

        assertEquals("totp-token", authenticator.getAccessToken());
        assertEquals("totp-token", authenticator.getAccessToken());
        assertEquals(1, requests.get(), "a valid token is not re-fetched");

        String body = lastBody.get();
        assertTrue(body.contains("\"key_type\":\"totp\""), body);
        assertTrue(body.matches(".*\"totp\":\"\\d{6}\".*"), "a six digit code was sent: " + body);
    }

    @Test
    @DisplayName("sends a freshly generated code on every refresh")
    void generatesANewCodePerRefresh() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/token/api/access", exchange -> {
            requests.incrementAndGet();
            // An already-expired token forces the next call to refresh.
            respond(exchange, 200, "{\"token\":\"t" + requests.get() + "\",\"expiry\":\"2000-01-01T00:00:00\"}");
        });
        server.start();

        CountingCodeSource generator = new CountingCodeSource();
        TotpGrowwAuthenticator authenticator = new TotpGrowwAuthenticator(
                HttpClient.newHttpClient(), baseUrl(), "api-key", generator);

        authenticator.getAccessToken();
        authenticator.getAccessToken();

        assertEquals(2, requests.get(), "an expired token is refreshed");
        assertEquals(2, generator.codesGenerated.get(), "each refresh mints its own code");
    }

    @Test
    @DisplayName("a rejected code fails with the flow named, not a generic auth error")
    void reportsWhichFlowFailed() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/token/api/access", exchange -> respond(exchange, 401, "invalid totp"));
        server.start();

        TotpGrowwAuthenticator authenticator = new TotpGrowwAuthenticator(
                HttpClient.newHttpClient(), baseUrl(), "api-key", SECRET);

        GrowwAuthException exception = assertThrows(GrowwAuthException.class, authenticator::getAccessToken);
        assertTrue(exception.getMessage().contains("TOTP"), exception.getMessage());
        assertTrue(exception.getMessage().contains("status 401"), exception.getMessage());
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    /** Counts how many codes were asked for, and never sits near a step boundary. */
    private static final class CountingCodeSource implements TotpCodeSource {
        private final AtomicInteger codesGenerated = new AtomicInteger();
        private final TotpGenerator delegate = new TotpGenerator(SECRET);

        @Override
        public String currentCode() {
            codesGenerated.incrementAndGet();
            return delegate.currentCode();
        }

        @Override
        public long secondsUntilNextCode(java.time.Instant at) {
            return 30;
        }
    }
}
