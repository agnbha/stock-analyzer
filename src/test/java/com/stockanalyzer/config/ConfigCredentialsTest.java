package com.stockanalyzer.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * application.properties is committed to a public repository and bundled into the jar, so it must
 * never carry a credential. Real values live in ~/.stock-analyzer.env, and an environment variable
 * outranks the file. A key pasted here once stayed in the repository's history for two months.
 */
class ConfigCredentialsTest {

    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.");
    private static final Pattern BASE32_SEED = Pattern.compile("(?m)^\\s*#?\\s*[A-Z2-7]{32}\\s*$");

    private static String shippedText() throws IOException {
        try (InputStream in = ConfigCredentialsTest.class.getResourceAsStream("/application.properties")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("the credential settings are blank in the shipped properties")
    void credentialSettingsAreBlank() throws IOException {
        Properties properties = new Properties();
        properties.load(new java.io.StringReader(shippedText()));

        for (String key : new String[]{"groww.api.key", "groww.api.secret", "groww.totp.secret",
                "screener.session.id"}) {
            String value = properties.getProperty(key, "");
            assertTrue(value.isBlank(), key + " has a value in application.properties; move it to "
                    + "~/.stock-analyzer.env (the value is not repeated here)");
        }
    }

    @Test
    @DisplayName("no token or authenticator seed is hiding anywhere in the file, comments included")
    void nothingSecretLookingAnywhere() throws IOException {
        String text = shippedText();

        assertFalse(JWT.matcher(text).find(), "a JWT-shaped token is in application.properties");
        assertFalse(BASE32_SEED.matcher(text).find(), "a Base32 authenticator seed is in application.properties");
    }
}
