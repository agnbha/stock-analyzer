package com.stockanalyzer.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrowwProfilesTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("with nothing configured there is one profile, \"main\", from the environment")
    void oneByDefault() {
        List<GrowwProfile> profiles = GrowwProfiles.load(config(Map.of("GROWW_AUTH_MODE", "totp",
                "GROWW_TOTP_TOKEN", "first-token", "GROWW_TOTP_SECRET", "FIRSTSEED")));

        assertEquals(1, profiles.size());
        GrowwProfile first = profiles.getFirst();
        assertEquals("main", first.name());
        assertTrue(first.primary());
        assertEquals("totp", first.authMode());
        assertEquals("first-token", first.totpToken());
        assertEquals("data/.groww-token.json", first.tokenCachePath(), "the first login keeps the cache it always had");
        assertEquals("the environment", first.source());
    }

    @Test
    @DisplayName("further accounts come from their env files, in order, each with its own name and token cache")
    void furtherAccountsFromFiles() throws IOException {
        Path second = write(".stock-analyzer-second.env", """
                GROWW_PROFILE_NAME=second
                GROWW_AUTH_MODE=totp
                GROWW_TOTP_TOKEN=second-token
                GROWW_TOTP_SECRET=SECONDSEED
                """);
        Path unnamed = write(".stock-analyzer3.env", "GROWW_API_KEY=k\nGROWW_API_SECRET=s\n");

        List<GrowwProfile> profiles = GrowwProfiles.load(config(Map.of("GROWW_PROFILE_NAME", "first",
                "GROWW_PROFILE_FILES", second + " , " + unnamed)));

        assertEquals(List.of("first", "second", "stock-analyzer3"), profiles.stream().map(GrowwProfile::name).toList(),
                "a file that names no account is called after the file");
        GrowwProfile two = profiles.get(1);
        assertEquals(1, two.position());
        assertFalse(two.primary());
        assertEquals("totp", two.authMode());
        assertEquals("second-token", two.totpToken());
        assertEquals("SECONDSEED", two.totpSecret());
        assertEquals("data/.groww-token-second.json", two.tokenCachePath());
        assertEquals(second.toString(), two.source());
        assertEquals("checksum", profiles.get(2).authMode(), "no mode given is the API key flow, as for the first");
        assertEquals("data/.groww-token-stock-analyzer3.json", profiles.get(2).tokenCachePath());
    }

    @Test
    @DisplayName("an account that cannot be used is left out, and the others still load")
    void unusableAccountsAreLeftOut() throws IOException {
        Path clash = write("clash.env", "GROWW_PROFILE_NAME=Main\nGROWW_TOTP_TOKEN=t\n");
        Path badName = write("bad.env", "GROWW_PROFILE_NAME=-starts with a dash\n");
        Path good = write("good.env", "GROWW_PROFILE_NAME=good\n");

        List<GrowwProfile> profiles = GrowwProfiles.load(config(Map.of("GROWW_PROFILE_FILES",
                directory.resolve("missing.env") + "," + clash + "," + badName + "," + good)));

        assertEquals(List.of("main", "good"), profiles.stream().map(GrowwProfile::name).toList(),
                "a missing file, a name already taken (in any case) and an unusable name are each skipped");
        assertEquals(1, profiles.get(1).position(), "positions stay without gaps");
    }

    @Test
    @DisplayName("the first account's name must be usable, because its rows and the dashboards depend on it")
    void theFirstNameMustBeUsable() {
        assertThrows(IllegalStateException.class,
                () -> GrowwProfiles.load(config(Map.of("GROWW_PROFILE_NAME", "a/b"))));
    }

    @Test
    @DisplayName("a profile in a log line never shows its credentials")
    void noCredentialsInText() throws IOException {
        Path file = write("x.env", "GROWW_PROFILE_NAME=x\nGROWW_TOTP_TOKEN=SECRET-TOKEN\nGROWW_TOTP_SECRET=SECRETSEED\n");
        String text = GrowwProfiles.load(config(Map.of("GROWW_PROFILE_FILES", file.toString()))).get(1).toString();
        assertFalse(text.contains("SECRET"), text);
    }

    @Test
    @DisplayName("~/ in a file name is the home folder, and a token cache without an extension gets a suffix")
    void paths() {
        assertEquals(Path.of(System.getProperty("user.home"), ".x.env"), GrowwProfiles.expandHome("~/.x.env"));
        assertEquals(Path.of("/abs/y.env"), GrowwProfiles.expandHome(" /abs/y.env "));
        assertEquals("cache-two-words", GrowwProfiles.tokenCacheFor("cache", "Two Words"));
        assertEquals("a.b/token-x.json", GrowwProfiles.tokenCacheFor("a.b/token.json", "x"));
    }

    private Path write(String name, String text) throws IOException {
        Path file = directory.resolve(name);
        Files.writeString(file, text);
        return file;
    }

    private static AppConfig config(Map<String, String> environment) {
        Properties properties = new Properties();
        properties.setProperty("groww.token.cache.path", "data/.groww-token.json");
        return AppConfig.of(properties, environment);
    }
}
