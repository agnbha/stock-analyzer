package com.stockanalyzer.auth;

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
     * @param mode       {@code checksum} (API key + secret) or {@code totp} (API key + authenticator code)
     * @param apiSecret  required for the checksum flow, ignored by TOTP
     * @param totpSecret the Base32 seed, required for the TOTP flow, ignored by checksum
     */
    public static GrowwAuthenticator create(String mode, HttpClient httpClient, String baseUrl,
                                            String apiKey, String apiSecret, String totpSecret) {
        String normalised = mode == null ? MODE_CHECKSUM : mode.trim().toLowerCase(Locale.ROOT);
        return switch (normalised) {
            case MODE_CHECKSUM -> {
                require(apiSecret, "groww.api.secret", MODE_CHECKSUM);
                yield new ChecksumGrowwAuthenticator(httpClient, baseUrl, apiKey, apiSecret);
            }
            case MODE_TOTP -> {
                require(totpSecret, "groww.totp.secret", MODE_TOTP);
                yield new TotpGrowwAuthenticator(httpClient, baseUrl, apiKey, totpSecret);
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
