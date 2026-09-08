package com.stockanalyzer.auth;

/**
 * Supplies a valid Groww API access token. Implementations decide how the
 * token is obtained (checksum flow, TOTP flow, a pre-generated static token, ...)
 * and whether/how it is cached and refreshed.
 */
public interface GrowwAuthenticator {

    /** Returns a currently-valid bearer token, refreshing it if necessary. */
    String getAccessToken();

    /**
     * When token requests are being held off and why, so a caller can say when
     * the API may be called again instead of finding out by being refused.
     * Empty when the flow is healthy.
     */
    default java.util.Optional<Cooldown> cooldown() {
        return java.util.Optional.empty();
    }

    /**
     * A hold on token requests. {@code remedy} is null when waiting is the whole
     * of the fix, and non-null when the wait cannot end without someone acting.
     */
    record Cooldown(java.time.Instant until, String reason, String remedy) {

        public boolean isActive() {
            return java.time.Instant.now().isBefore(until);
        }

        /** The wait in local time, which is how a person reads a clock. */
        public String untilLocalTime() {
            return until.atZone(java.time.ZoneId.systemDefault())
                    .toLocalTime().withNano(0).toString();
        }
    }
}
