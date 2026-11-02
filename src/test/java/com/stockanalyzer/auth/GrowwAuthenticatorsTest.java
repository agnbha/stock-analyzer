package com.stockanalyzer.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrowwAuthenticatorsTest {

    private static final com.stockanalyzer.client.RateLimiter NO_LIMIT = () -> { };

    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private GrowwAuthenticator create(String mode, String apiKey, String apiSecret, String totpToken,
                                      String totpSecret) {
        return GrowwAuthenticators.create(mode, httpClient, "http://localhost", apiKey, apiSecret,
                totpToken, totpSecret, TokenCache.none(), NO_LIMIT);
    }

    /** The checksum flow's pair: Groww's "API key" and "API secret". */
    private GrowwAuthenticator checksum(String apiKey, String apiSecret) {
        return create("checksum", apiKey, apiSecret, null, null);
    }

    /** The TOTP flow's pair: Groww's "TOTP token" and "TOTP secret". */
    private GrowwAuthenticator totp(String totpToken, String totpSecret) {
        return create("totp", null, null, totpToken, totpSecret);
    }

    @Test
    @DisplayName("picks the flow named in configuration")
    void picksTheConfiguredFlow() {
        assertInstanceOf(ChecksumGrowwAuthenticator.class, checksum("key", "secret"));
        assertInstanceOf(TotpGrowwAuthenticator.class, totp("token", SECRET));
        assertInstanceOf(TotpGrowwAuthenticator.class, create("  TOTP  ", null, null, "token", SECRET),
                "case and stray spaces should not matter");
    }

    @Test
    @DisplayName("defaults to the checksum flow when nothing is set")
    void defaultsToChecksum() {
        assertInstanceOf(ChecksumGrowwAuthenticator.class, create(null, "key", "secret", null, null));
    }

    @Test
    @DisplayName("each flow needs only its own pair: the TOTP flow does not ask for an API key or secret")
    void eachFlowReadsOnlyItsOwnPair() {
        assertInstanceOf(TotpGrowwAuthenticator.class, create("totp", null, null, "token", SECRET));
        assertInstanceOf(ChecksumGrowwAuthenticator.class, create("checksum", "key", "secret", null, null));
    }

    @Test
    @DisplayName("a TOTP setup from before the token had its own setting still works with the token in the API key")
    void totpTokenFallsBackToTheApiKey() {
        assertInstanceOf(TotpGrowwAuthenticator.class, create("totp", "token-in-the-old-place", null, null, SECRET));
        assertInstanceOf(TotpGrowwAuthenticator.class, create("totp", "old", null, " ", SECRET));
    }

    @Test
    @DisplayName("names the credential that is actually missing, in Groww's words and the matching env var")
    void reportsTheMissingCredential() {
        GrowwAuthException missingSecret = assertThrows(GrowwAuthException.class, () -> checksum("key", null));
        assertTrue(missingSecret.getMessage().contains("groww.api.secret"), missingSecret.getMessage());
        assertTrue(missingSecret.getMessage().contains("GROWW_API_SECRET"), missingSecret.getMessage());

        GrowwAuthException missingKey = assertThrows(GrowwAuthException.class, () -> checksum(null, "secret"));
        assertTrue(missingKey.getMessage().contains("GROWW_API_KEY"), missingKey.getMessage());

        GrowwAuthException missingToken = assertThrows(GrowwAuthException.class, () -> totp(null, SECRET));
        assertTrue(missingToken.getMessage().contains("groww.totp.token"), missingToken.getMessage());
        assertTrue(missingToken.getMessage().contains("GROWW_TOTP_TOKEN"), missingToken.getMessage());

        GrowwAuthException missingSeed = assertThrows(GrowwAuthException.class, () -> totp("token", " "));
        assertTrue(missingSeed.getMessage().contains("groww.totp.secret"), missingSeed.getMessage());
        assertTrue(missingSeed.getMessage().contains("GROWW_TOTP_SECRET"), missingSeed.getMessage());
    }

    @Test
    @DisplayName("an unknown mode says what the valid ones are")
    void rejectsUnknownModes() {
        GrowwAuthException exception = assertThrows(GrowwAuthException.class,
                () -> create("oauth", "key", "secret", "token", SECRET));
        assertTrue(exception.getMessage().contains("checksum"), exception.getMessage());
        assertTrue(exception.getMessage().contains("totp"), exception.getMessage());
    }
}
