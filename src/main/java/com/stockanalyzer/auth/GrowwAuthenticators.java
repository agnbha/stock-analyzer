package com.stockanalyzer.auth;

import com.stockanalyzer.client.RateLimiter;

import java.net.http.HttpClient;
import java.util.Locale;

/**
 * Chooses a token flow from configuration, so every entry point wires
 * authentication the same way and adding a third flow touches one place.
 */
public final class GrowwAuthenticators {

    public static final String MODE_CHECKSUM = "checksum";
    public static final String MODE_TOTP = "totp";

    private GrowwAuthenticators() {
    }

    /**
     * Groww issues a pair of credentials per flow, under these names, and so does this:
     * <ul>
     *   <li>{@code checksum}: the <b>API key</b> and the <b>API secret</b>;</li>
     *   <li>{@code totp}: the <b>TOTP token</b> and the <b>TOTP secret</b>. The token is what
     *       Groww's SDK passes as {@code api_key}; the secret is the Base32 seed that the
     *       six-digit code is generated from, and is never sent.</li>
     * </ul>
     * Each flow reads only its own pair. For a TOTP setup written before the token had a name of its
     * own, a blank {@code totpToken} falls back to {@code apiKey}, which is where it used to go.
     *
     * @param mode       {@code checksum} or {@code totp}
     * @param apiKey     the API key (checksum flow)
     * @param apiSecret  the API secret (checksum flow)
     * @param totpToken  the TOTP token (TOTP flow)
     * @param totpSecret the TOTP secret, a Base32 seed (TOTP flow)
     */
    public static GrowwAuthenticator create(String mode, HttpClient httpClient, String baseUrl,
                                            String apiKey, String apiSecret,
                                            String totpToken, String totpSecret,
                                            TokenCache tokenCache, RateLimiter rateLimiter) {
        String normalised = mode == null ? MODE_CHECKSUM : mode.trim().toLowerCase(Locale.ROOT);
        return switch (normalised) {
            case MODE_CHECKSUM -> {
                require(apiKey, "groww.api.key", MODE_CHECKSUM);
                require(apiSecret, "groww.api.secret", MODE_CHECKSUM);
                yield new ChecksumGrowwAuthenticator(httpClient, baseUrl, apiKey, apiSecret,
                        tokenCache, rateLimiter);
            }
            case MODE_TOTP -> {
                String token = totpToken == null || totpToken.isBlank() ? apiKey : totpToken;
                require(token, "groww.totp.token", MODE_TOTP);
                require(totpSecret, "groww.totp.secret", MODE_TOTP);
                yield new TotpGrowwAuthenticator(httpClient, baseUrl, token, totpSecret,
                        tokenCache, rateLimiter);
            }
            default -> throw new GrowwAuthException("Unknown groww.auth.mode '" + mode
                    + "'. Use '" + MODE_CHECKSUM + "' or '" + MODE_TOTP + "'.");
        };
    }

    private static void require(String value, String key, String mode) {
        if (value == null || value.isBlank()) {
            throw new GrowwAuthException("groww.auth.mode is '" + mode + "', which needs " + key
                    + ". Set it in application.properties, or as the environment variable "
                    + key.toUpperCase(Locale.ROOT).replace('.', '_') + ".");
        }
    }
}
