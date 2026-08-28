package com.stockanalyzer.auth;

/**
 * Supplies a valid Groww API access token. Implementations decide how the
 * token is obtained (checksum flow, TOTP flow, a pre-generated static token, ...)
 * and whether/how it is cached and refreshed.
 */
public interface GrowwAuthenticator {

    /** Returns a currently-valid bearer token, refreshing it if necessary. */
    String getAccessToken();
}
