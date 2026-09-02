package com.stockanalyzer.auth;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChecksumGrowwAuthenticatorTest {

    private static final com.stockanalyzer.client.RateLimiter NO_LIMIT = () -> { };

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void cachesSuccessfulTokenAndSendsApprovalRequest() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/token/api/access", exchange -> {
            requests.incrementAndGet();
            assertEquals("Bearer api-key", exchange.getRequestHeaders().getFirst("Authorization"));
            String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(body.contains("\"key_type\":\"approval\""));
            respond(exchange, 200, "{\"token\":\"access-token\",\"expiry\":\"2099-01-01T00:00:00\"}");
        });
        server.start();

        ChecksumGrowwAuthenticator authenticator = new ChecksumGrowwAuthenticator(
                HttpClient.newHttpClient(), baseUrl(), "api-key", "api-secret",
                TokenCache.none(), NO_LIMIT);

        assertEquals("access-token", authenticator.getAccessToken());
        assertEquals("access-token", authenticator.getAccessToken());
        assertEquals(1, requests.get());
    }

    @Test
    void rejectsBlankTokenResponse() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/token/api/access", exchange -> respond(exchange, 200,
                "{\"token\":\" \"}"));
        server.start();

        ChecksumGrowwAuthenticator authenticator = new ChecksumGrowwAuthenticator(
                HttpClient.newHttpClient(), baseUrl(), "key", "secret",
                TokenCache.none(), NO_LIMIT);

        GrowwAuthException exception = assertThrows(GrowwAuthException.class, authenticator::getAccessToken);
        assertTrue(exception.getMessage().contains("did not contain a token"));
    }

    @Test
    void rejectsFailedTokenRequest() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/token/api/access", exchange -> respond(exchange, 401, "unauthorized"));
        server.start();

        ChecksumGrowwAuthenticator authenticator = new ChecksumGrowwAuthenticator(
                HttpClient.newHttpClient(), baseUrl(), "key", "secret",
                TokenCache.none(), NO_LIMIT);

        GrowwAuthException exception = assertThrows(GrowwAuthException.class, authenticator::getAccessToken);
        assertTrue(exception.getMessage().contains("status 401"));
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
