package com.stockanalyzer.auth;

import com.stockanalyzer.client.RateLimiter;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * Groww's API-key + secret ("approval") token flow:
 * checksum = SHA-256(apiSecret + epochSecondsTimestamp), hex-encoded.
 *
 * <p>Tokens are cached and only refreshed once expired, since Groww access
 * tokens are valid until 6:00 AM the day after issue.
 *
 * <p><b>Note:</b> the exact checksum concatenation order/encoding is derived
 * from Groww's published cURL docs (groww.in/trade-api/docs/curl); confirm
 * against the current docs if authentication starts failing, since broker
 * APIs occasionally revise this without a major version bump.
 *
 * <p>See {@link TotpGrowwAuthenticator} for the two-factor alternative.
 */
public final class ChecksumGrowwAuthenticator extends AbstractGrowwTokenAuthenticator {

    private final String apiSecret;

    public ChecksumGrowwAuthenticator(HttpClient httpClient, String baseUrl, String apiKey,
                                      String apiSecret, TokenCache tokenCache, RateLimiter rateLimiter) {
        super(httpClient, baseUrl, apiKey, tokenCache, rateLimiter);
        this.apiSecret = apiSecret;
    }

    @Override
    protected String flowName() {
        return "checksum";
    }

    @Override
    protected Map<String, Object> tokenRequestPayload() {
        long epochSeconds = Instant.now().getEpochSecond();
        return Map.of(
                "key_type", "approval",
                "checksum", sha256Hex(apiSecret + epochSeconds),
                "timestamp", String.valueOf(epochSeconds));
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new GrowwAuthException("SHA-256 not available", e);
        }
    }
}
