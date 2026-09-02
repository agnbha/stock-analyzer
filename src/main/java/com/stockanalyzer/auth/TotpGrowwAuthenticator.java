package com.stockanalyzer.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.stockanalyzer.client.RateLimiter;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.Map;

/**
 * Groww's two-factor token flow: exchange the API key plus a current TOTP code
 * for an access token, the same pairing the Python SDK exposes as
 * {@code GrowwAPI.get_access_token(api_key=..., totp=...)}.
 *
 * <p>The code is generated fresh on every refresh from the Base32 seed, never
 * cached - a code is only valid for its 30-second step. Because of that, this
 * flow depends on the machine's clock being roughly correct; a server whose
 * time has drifted will see its tokens rejected with a 4xx that looks like a
 * bad credential. If a refresh lands in the last second or two of a step, the
 * request is deliberately held until the next code is minted rather than sent
 * with one about to expire.
 *
 * <p><b>Note:</b> as with the checksum flow, the request field names are
 * reconstructed from Groww's published docs. If token requests start failing
 * with 4xx while the code itself verifies in an authenticator app, re-check the
 * payload shape at groww.in/trade-api/docs before assuming the seed is wrong.
 */
public final class TotpGrowwAuthenticator extends AbstractGrowwTokenAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(TotpGrowwAuthenticator.class);

    /** Below this much life left, the current code is not worth sending. */
    private static final long MINIMUM_CODE_LIFE_SECONDS = 2;

    private final TotpCodeSource codeSource;

    public TotpGrowwAuthenticator(HttpClient httpClient, String baseUrl, String apiKey,
                                  String totpSecret, TokenCache tokenCache, RateLimiter rateLimiter) {
        this(httpClient, baseUrl, apiKey, new TotpGenerator(totpSecret), tokenCache, rateLimiter);
    }

    public TotpGrowwAuthenticator(HttpClient httpClient, String baseUrl, String apiKey,
                                  TotpCodeSource codeSource, TokenCache tokenCache,
                                  RateLimiter rateLimiter) {
        super(httpClient, baseUrl, apiKey, tokenCache, rateLimiter);
        this.codeSource = codeSource;
    }

    @Override
    protected String flowName() {
        return "TOTP";
    }

    @Override
    protected Map<String, Object> tokenRequestPayload() {
        waitOutAnExpiringCode();
        return Map.of(
                "key_type", "totp",
                "totp", codeSource.currentCode());
    }

    private void waitOutAnExpiringCode() {
        long remaining = codeSource.secondsUntilNextCode(Instant.now());
        if (remaining > MINIMUM_CODE_LIFE_SECONDS) {
            return;
        }
        log.debug("Current TOTP code expires in {}s; waiting for the next one", remaining);
        try {
            Thread.sleep(remaining * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GrowwAuthException("Interrupted while waiting for the next TOTP code", e);
        }
    }
}
