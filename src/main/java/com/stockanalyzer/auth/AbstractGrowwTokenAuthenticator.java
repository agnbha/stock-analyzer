package com.stockanalyzer.auth;

import com.stockanalyzer.client.RateLimiter;
import com.stockanalyzer.util.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(AbstractGrowwTokenAuthenticator.class);
    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.ISO_DATE_TIME;
    private static final long FALLBACK_TTL_SECONDS = 6 * 3600;
    /** Renew a little early rather than racing the expiry. */
    private static final long RENEW_MARGIN_SECONDS = 120;
    private static final int MAX_TOKEN_ATTEMPTS = 3;
    private static final long THROTTLE_COOLDOWN_SECONDS = 60;

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final TokenCache tokenCache;
    private final RateLimiter rateLimiter;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile String cachedToken;
    private volatile Instant cachedTokenExpiry = Instant.EPOCH;
    private volatile Instant retryNotBefore = Instant.EPOCH;

    AbstractGrowwTokenAuthenticator(HttpClient httpClient, String baseUrl, String apiKey,
                                    TokenCache tokenCache, RateLimiter rateLimiter) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.tokenCache = tokenCache;
        this.rateLimiter = rateLimiter;
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
            // A token minted by an earlier process is still a valid token. Short
            // CLI runs would otherwise mint one each, which is what breaches the
            // token endpoint's quota.
            adoptCachedToken();
            if (isTokenUsable()) {
                return cachedToken;
            }
            if (Instant.now().isBefore(retryNotBefore)) {
                throw new GrowwAuthException("Token requests are being throttled; not retrying before "
                        + retryNotBefore + ". Every caller waits together rather than each retrying.");
            }
            refreshToken();
            return cachedToken;
        } finally {
            lock.unlock();
        }
    }

    private boolean isTokenUsable() {
        return cachedToken != null
                && Instant.now().plusSeconds(RENEW_MARGIN_SECONDS).isBefore(cachedTokenExpiry);
    }

    private void adoptCachedToken() {
        tokenCache.load(cacheKey()).ifPresent(entry -> {
            if (entry.isUsableAt(Instant.now().plusSeconds(RENEW_MARGIN_SECONDS))) {
                cachedToken = entry.token();
                cachedTokenExpiry = entry.expiry();
                log.debug("Reusing a token from the shared cache, valid until {}", entry.expiry());
            }
        });
    }

    /** Identifies whose token this is, without putting the credential in the file. */
    private String cacheKey() {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((flowName() + '|' + apiKey).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (Exception e) {
            throw new GrowwAuthException("SHA-256 unavailable", e);
        }
    }

    private void refreshToken() {
        GrowwAuthException last = null;
        for (int attempt = 0; attempt < MAX_TOKEN_ATTEMPTS; attempt++) {
            try {
                attemptRefresh();
                tokenCache.save(cacheKey(), new TokenCache.Entry(cachedToken, cachedTokenExpiry));
                return;
            } catch (GrowwAuthException e) {
                last = e;
                if (!e.isThrottled() || attempt == MAX_TOKEN_ATTEMPTS - 1) {
                    break;
                }
                long waitMillis = e.retryAfterMillis() > 0
                        ? e.retryAfterMillis()
                        : 1000L * (1L << attempt);
                log.warn("Token request throttled; waiting {} ms before attempt {} of {}",
                        waitMillis, attempt + 2, MAX_TOKEN_ATTEMPTS);
                sleep(waitMillis);
            }
        }
        if (last != null && last.isThrottled()) {
            // Hold every caller off together instead of each one retrying.
            retryNotBefore = Instant.now().plusSeconds(THROTTLE_COOLDOWN_SECONDS);
        }
        throw last;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GrowwAuthException("Interrupted while waiting to retry the token request", e);
        }
    }

    private void attemptRefresh() {
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

        // The token endpoint has its own quota, so it has to spend a slot too -
        // rate limiting only the data requests left this path unprotected.
        rateLimiter.acquire();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new GrowwAuthException("Failed to reach Groww token endpoint for the "
                    + flowName() + " flow", e);
        }

        if (response.statusCode() / 100 != 2) {
            long retryAfter = response.headers().firstValue("Retry-After")
                    .map(value -> {
                        try {
                            return (long) (Double.parseDouble(value.trim()) * 1000);
                        } catch (NumberFormatException ignored) {
                            return 0L;
                        }
                    }).orElse(0L);
            if (response.statusCode() == 429) {
                rateLimiter.penalise(retryAfter > 0 ? retryAfter : 2_000);
            }
            throw new GrowwAuthException("Groww " + flowName() + " token request failed with status "
                    + response.statusCode() + ": " + response.body(),
                    response.statusCode(), retryAfter);
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
