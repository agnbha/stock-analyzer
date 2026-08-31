package com.stockanalyzer.auth;

import com.stockanalyzer.util.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The parts every token flow shares: exchange a payload for a token at
 * {@code POST /v1/token/api/access}, cache it, and refresh once expired.
 *
 * <p>Subclasses supply only what differs - the request payload - so adding a
 * flow means writing one method, not another copy of the HTTP handling.
 */
abstract class AbstractGrowwTokenAuthenticator implements GrowwAuthenticator {

    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.ISO_DATE_TIME;
    private static final long FALLBACK_TTL_SECONDS = 6 * 3600;

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile String cachedToken;
    private volatile Instant cachedTokenExpiry = Instant.EPOCH;

    AbstractGrowwTokenAuthenticator(HttpClient httpClient, String baseUrl, String apiKey) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    /** The JSON body identifying this flow and proving the caller holds the credential. */
    protected abstract Map<String, Object> tokenRequestPayload();

    /** Short name used in error messages, so a failure says which flow failed. */
    protected abstract String flowName();

    @Override
    public final String getAccessToken() {
        if (isTokenUsable()) {
            return cachedToken;
        }
        lock.lock();
        try {
            if (isTokenUsable()) {
                return cachedToken;
            }
            refreshToken();
            return cachedToken;
        } finally {
            lock.unlock();
        }
    }

    private boolean isTokenUsable() {
        return cachedToken != null && Instant.now().isBefore(cachedTokenExpiry);
    }

    private void refreshToken() {
        String body;
        try {
            body = JsonMapper.INSTANCE.writeValueAsString(tokenRequestPayload());
        } catch (Exception e) {
            throw new GrowwAuthException("Failed to serialize the " + flowName() + " token request", e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/token/api/access"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new GrowwAuthException("Failed to reach Groww token endpoint for the "
                    + flowName() + " flow", e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new GrowwAuthException("Groww " + flowName() + " token request failed with status "
                    + response.statusCode() + ": " + response.body());
        }

        TokenResponse tokenResponse;
        try {
            tokenResponse = JsonMapper.INSTANCE.readValue(response.body(), TokenResponse.class);
        } catch (Exception e) {
            throw new GrowwAuthException("Failed to parse Groww token response: " + response.body(), e);
        }

        if (tokenResponse.token() == null || tokenResponse.token().isBlank()) {
            throw new GrowwAuthException("Groww token response did not contain a token: " + response.body());
        }

        cachedToken = tokenResponse.token();
        cachedTokenExpiry = parseExpiry(tokenResponse.expiry());
    }

    private static Instant parseExpiry(String expiry) {
        if (expiry == null || expiry.isBlank()) {
            return Instant.now().plusSeconds(FALLBACK_TTL_SECONDS);
        }
        try {
            return LocalDateTime.parse(expiry, EXPIRY_FORMAT).atZone(ZoneId.systemDefault()).toInstant();
        } catch (Exception e) {
            // A shape we do not recognise is not worth failing over; re-authenticate sooner instead.
            return Instant.now().plusSeconds(FALLBACK_TTL_SECONDS);
        }
    }
}
