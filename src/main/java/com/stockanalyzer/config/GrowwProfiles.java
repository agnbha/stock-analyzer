package com.stockanalyzer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds the profiles from the configuration: the first from the environment, the others from the env files named in
 * {@code groww.profile.files}. A further profile that cannot be used - its file missing, its name clashing - is left
 * out with an error in the log rather than stopping the process, because the first profile's stock data and journal
 * must keep working whatever is wrong with another account's file.
 */
public final class GrowwProfiles {

    private static final Logger log = LoggerFactory.getLogger(GrowwProfiles.class);
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} ._-]{0,39}");

    private GrowwProfiles() {
    }

    public static List<GrowwProfile> load(AppConfig config) {
        String firstName = config.growwProfileName();
        if (!NAME.matcher(firstName).matches()) {
            throw new IllegalStateException("groww.profile.name '" + firstName + "' is not a usable name: use up to 40 "
                    + "letters, digits, spaces, dots, dashes or underscores, starting with a letter or digit");
        }
        List<GrowwProfile> profiles = new ArrayList<>();
        profiles.add(new GrowwProfile(firstName, 0, config.growwAuthMode(), config.growwApiKeyOrNull(),
                config.growwApiSecretOrNull(), config.growwTotpTokenOrNull(), config.growwTotpSecretOrNull(),
                config.tokenCachePath(), "the environment"));
        Set<String> taken = new HashSet<>();
        taken.add(firstName.toLowerCase(Locale.ROOT));

        for (String entry : config.growwProfileFiles()) {
            Path file = expandHome(entry);
            if (!Files.isReadable(file)) {
                log.error("Profile file {} (groww.profile.files) is missing or unreadable; that account is left out",
                        file);
                continue;
            }
            warnIfOthersCanRead(file);
            Map<String, String> values = EnvFile.read(file);
            String name = values.getOrDefault("GROWW_PROFILE_NAME", nameFromFile(file)).strip();
            if (!NAME.matcher(name).matches()) {
                log.error("Profile file {} names its account '{}', which is not a usable name; that account is "
                        + "left out", file, name);
                continue;
            }
            if (!taken.add(name.toLowerCase(Locale.ROOT))) {
                log.error("Profile file {} names its account '{}', which another profile already uses; that account is "
                        + "left out (two logins under one name would merge their journals)", file, name);
                continue;
            }
            profiles.add(new GrowwProfile(name, profiles.size(),
                    values.getOrDefault("GROWW_AUTH_MODE", "checksum"), values.get("GROWW_API_KEY"),
                    values.get("GROWW_API_SECRET"), values.get("GROWW_TOTP_TOKEN"), values.get("GROWW_TOTP_SECRET"),
                    tokenCacheFor(config.tokenCachePath(), name), file.toString()));
        }
        return List.copyOf(profiles);
    }

    /** {@code ~/.stock-analyzer-second.env} reads as "stock-analyzer-second": no leading dot, no extension. */
    static String nameFromFile(Path file) {
        String name = file.getFileName().toString();
        if (name.startsWith(".")) {
            name = name.substring(1);
        }
        if (name.endsWith(".env")) {
            name = name.substring(0, name.length() - ".env".length());
        }
        return name;
    }

    /** data/.groww-token.json becomes data/.groww-token-second.json: one cached token per login. */
    static String tokenCacheFor(String firstProfilePath, String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (slug.isEmpty()) {
            slug = Integer.toHexString(name.hashCode());
        }
        int dot = firstProfilePath.lastIndexOf('.');
        int slash = Math.max(firstProfilePath.lastIndexOf('/'), firstProfilePath.lastIndexOf('\\'));
        return dot > slash + 1
                ? firstProfilePath.substring(0, dot) + "-" + slug + firstProfilePath.substring(dot)
                : firstProfilePath + "-" + slug;
    }

    static Path expandHome(String entry) {
        String path = entry.strip();
        String home = System.getProperty("user.home");
        if (path.equals("~")) {
            return Path.of(home);
        }
        if (path.startsWith("~/")) {
            return Path.of(home, path.substring(2));
        }
        if (path.startsWith("$HOME/")) {
            return Path.of(home, path.substring("$HOME/".length()));
        }
        return Path.of(path);
    }

    private static void warnIfOthersCanRead(Path file) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            if (permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_READ)) {
                log.warn("Profile file {} can be read by other users; it holds a login, so `chmod 600` it", file);
            }
        } catch (IOException | UnsupportedOperationException e) {
            // Not a POSIX file system, or the check itself failed: nothing to warn about.
        }
    }
}
