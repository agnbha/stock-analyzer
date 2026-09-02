package com.stockanalyzer.auth;

import java.time.Instant;
import java.util.Optional;

/**
 * Somewhere a token can outlive the process that fetched it.
 *
 * <p>Without this, every CLI invocation mints a fresh token, and a handful of
 * short-lived commands in quick succession is enough to breach the token
 * endpoint's own quota - which is tighter than the data endpoints', and which
 * no amount of rate limiting on candle requests protects.
 */
public interface TokenCache {

    Optional<Entry> load(String key);

    void save(String key, Entry entry);

    /** A cache that remembers nothing; the previous behaviour. */
    static TokenCache none() {
        return new TokenCache() {
            @Override
            public Optional<Entry> load(String key) {
                return Optional.empty();
            }

            @Override
            public void save(String key, Entry entry) {
            }
        };
    }

    record Entry(String token, Instant expiry) {

        public boolean isUsableAt(Instant moment) {
            return token != null && !token.isBlank() && moment.isBefore(expiry);
        }
    }
}
