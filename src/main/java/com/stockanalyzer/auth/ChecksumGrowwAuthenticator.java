package com.stockanalyzer.auth;

import com.stockanalyzer.util.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implements Groww's API-key + secret ("approval") token flow:
 * checksum = SHA-256(apiSecret + epochSecondsTimestamp), hex-encoded.
 *
 * <p>Tokens are cached and only refreshed once expired, since Groww access
 * tokens are valid until 6:00 AM the day after issue.
 *
 * <p><b>Note:</b> the exact checksum concatenation order/encoding is derived
 * from Groww's published cURL docs (groww.in/trade-api/docs/curl); confirm
 * against the current docs if authentication starts failing, since broker
 * APIs occasionally revise this without a major version bump.
 */
public final class ChecksumGrowwAuthenticator implements GrowwAuthenticator {

    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.ISO_DATE_TIME;

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String apiSecret;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile String cachedToken;
    private volatile Instant cachedTokenExpiry = Instant.EPOCH;

    public ChecksumGrowwAuthenticator(HttpClient httpClient, String baseUrl, String apiKey, String apiSecret) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
    }

    @Override
    public String getAccessToken() {
        if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiry)) {
            return cachedToken;
        }
        lock.lock();
        try {
            if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiry)) {
                return cachedToken;
            }
            refreshToken();
            return cachedToken;
        } finally {
            lock.unlock();
        }
    }

    private void refreshToken() {
        long epochSeconds = Instant.now().getEpochSecond();
        String checksum = sha256Hex(apiSecret + epochSeconds);

        String body;
        try {
            body = JsonMapper.INSTANCE.writeValueAsString(Map.of(
                    "key_type", "approval",
                    "checksum", checksum,
                    "timestamp", String.valueOf(epochSeconds)));
        } catch (Exception e) {
            throw new GrowwAuthException("Failed to serialize token request", e);
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
            throw new GrowwAuthException("Failed to reach Groww token endpoint", e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new GrowwAuthException(
                    "Groww token request failed with status " + response.statusCode() + ": " + response.body());
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

    private Instant parseExpiry(String expiry) {
        if (expiry == null || expiry.isBlank()) {
            // Fall back to a conservative 6-hour TTL if the response omits expiry.
            return Instant.now().plusSeconds(6 * 3600);
        }
        try {
            return LocalDateTime.parse(expiry, EXPIRY_FORMAT).atZone(java.time.ZoneId.systemDefault()).toInstant();
        } catch (Exception e) {
            return Instant.now().plusSeconds(6 * 3600);
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new GrowwAuthException("SHA-256 not available", e);
        }
    }
}
