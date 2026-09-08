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

    @Test
    void aRejectedSessionIsNotAskedAgainOnEveryCall() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        // What Groww actually returned: the credential is fine, the session behind
        // it needs approving. No amount of retrying changes that.
        server.createContext("/token/api/access", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 403, "{\"error\":{\"errorCode\":\"403\","
                    + "\"errorMessage\":\"Session approval required before generating token\"}}");
        });
        server.start();

        ChecksumGrowwAuthenticator authenticator = new ChecksumGrowwAuthenticator(
                HttpClient.newHttpClient(), baseUrl(), "key", "secret",
                TokenCache.none(), NO_LIMIT);

        assertThrows(GrowwAuthException.class, authenticator::getAccessToken);
        assertEquals(1, requests.get());

        // The polling loop calls again for every symbol on every tick. Before the
        // cooldown each of those was another token request, and it was that pile -
        // not the poll rate - that earned the 429s.
        for (int caller = 0; caller < 20; caller++) {
            GrowwAuthException refused = assertThrows(GrowwAuthException.class, authenticator::getAccessToken);
            assertTrue(refused.getMessage().contains("held off until"),
                    "callers should be told when they may try again, got: " + refused.getMessage());
        }
        assertEquals(1, requests.get(), "the endpoint must not be asked again during the hold");
    }

    @Test
    void theCooldownSaysWhenAndWhatToDoAboutIt() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/token/api/access", exchange -> respond(exchange, 403, "session approval required"));
        server.start();

        ChecksumGrowwAuthenticator authenticator = new ChecksumGrowwAuthenticator(
                HttpClient.newHttpClient(), baseUrl(), "key", "secret",
                TokenCache.none(), NO_LIMIT);
        assertThrows(GrowwAuthException.class, authenticator::getAccessToken);

        GrowwAuthenticator.Cooldown cooldown = authenticator.cooldown().orElseThrow();
        assertTrue(cooldown.isActive());
        assertTrue(cooldown.reason().contains("403"), "the reason names the status");
        assertTrue(cooldown.remedy() != null && cooldown.remedy().contains("groww.in"),
                "a block only a person can clear must say what to do");
        // A wall-clock time, not an ISO instant in UTC that has to be converted.
        assertTrue(cooldown.untilLocalTime().matches("\\d{2}:\\d{2}:\\d{2}"),
                "got: " + cooldown.untilLocalTime());
    }

    @Test
    void aThrottleIsHeldMuchMoreBrieflyThanARejection() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/token/api/access", exchange -> respond(exchange, 429, "slow down"));
        server.start();

        ChecksumGrowwAuthenticator authenticator = new ChecksumGrowwAuthenticator(
                HttpClient.newHttpClient(), baseUrl(), "key", "secret",
                TokenCache.none(), NO_LIMIT);
        assertThrows(GrowwAuthException.class, authenticator::getAccessToken);

        GrowwAuthenticator.Cooldown cooldown = authenticator.cooldown().orElseThrow();
        // A busy server comes right on its own, so waiting it out is the fix and
        // there is nothing for anyone to do.
        assertEquals(null, cooldown.remedy());
        long seconds = java.time.Duration.between(java.time.Instant.now(), cooldown.until()).toSeconds();
        assertTrue(seconds > 0 && seconds <= 60, "throttle hold should be about a minute, got " + seconds);
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
