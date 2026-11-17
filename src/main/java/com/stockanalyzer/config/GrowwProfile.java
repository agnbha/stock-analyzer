package com.stockanalyzer.config;

/**
 * One Groww account: a name and the login that reads its fills, balance and holdings. The first profile is the one
 * whose credentials are in the process environment (from {@code ~/.stock-analyzer.env}); its login also fetches the
 * stock data every profile shares. Further profiles come from their own env files ({@code groww.profile.files}).
 *
 * @param position       0 for the first profile, then in the order configured
 * @param tokenCachePath where this login's access token is cached between processes
 * @param source         where the credentials came from, for messages: "the environment" or a file path
 */
public record GrowwProfile(String name, int position, String authMode, String apiKey, String apiSecret,
                           String totpToken, String totpSecret, String tokenCachePath, String source) {

    /** The first profile's name when {@code groww.profile.name} is not set. */
    public static final String DEFAULT_NAME = "main";

    public boolean primary() {
        return position == 0;
    }

    /** Never the credentials: a profile ends up in log lines. */
    @Override
    public String toString() {
        return "GrowwProfile[" + name + ", #" + position + ", " + authMode + ", from " + source + "]";
    }
}
