package com.stockanalyzer.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrowwAuthenticatorsTest {

    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private GrowwAuthenticator create(String mode, String apiSecret, String totpSecret) {
        return GrowwAuthenticators.create(mode, httpClient, "http://localhost", "key", apiSecret, totpSecret);
    }

    @Test
    @DisplayName("picks the flow named in configuration")
    void picksTheConfiguredFlow() {
        assertInstanceOf(ChecksumGrowwAuthenticator.class, create("checksum", "secret", null));
        assertInstanceOf(TotpGrowwAuthenticator.class, create("totp", null, SECRET));
        assertInstanceOf(TotpGrowwAuthenticator.class, create("  TOTP  ", null, SECRET),
                "case and stray spaces should not matter");
    }

    @Test
    @DisplayName("defaults to the checksum flow when nothing is set")
    void defaultsToChecksum() {
        assertInstanceOf(ChecksumGrowwAuthenticator.class, create(null, "secret", null));
    }

    @Test
    @DisplayName("names the credential that is actually missing")
    void reportsTheMissingCredential() {
        GrowwAuthException missingSecret = assertThrows(GrowwAuthException.class,
                () -> create("checksum", null, SECRET));
        assertTrue(missingSecret.getMessage().contains("groww.api.secret"), missingSecret.getMessage());
        assertTrue(missingSecret.getMessage().contains("GROWW_API_SECRET"), missingSecret.getMessage());

        GrowwAuthException missingSeed = assertThrows(GrowwAuthException.class,
                () -> create("totp", "secret", " "));
        assertTrue(missingSeed.getMessage().contains("groww.totp.secret"), missingSeed.getMessage());
    }

    @Test
    @DisplayName("an unknown mode says what the valid ones are")
    void rejectsUnknownModes() {
        GrowwAuthException exception = assertThrows(GrowwAuthException.class,
                () -> create("oauth", "secret", SECRET));
        assertTrue(exception.getMessage().contains("checksum"), exception.getMessage());
        assertTrue(exception.getMessage().contains("totp"), exception.getMessage());
    }
}
