package com.stockanalyzer.auth;

public class GrowwAuthException extends RuntimeException {

    private final int statusCode;
    private final long retryAfterMillis;

    public GrowwAuthException(String message) {
        this(message, 0, 0);
    }

    public GrowwAuthException(String message, int statusCode, long retryAfterMillis) {
        super(message);
        this.statusCode = statusCode;
        this.retryAfterMillis = retryAfterMillis;
    }

    public int statusCode() {
        return statusCode;
    }

    /** What the server asked us to wait, if it said; 0 otherwise. */
    public long retryAfterMillis() {
        return retryAfterMillis;
    }

    /** A 429 is worth waiting out; anything else will not improve on retry. */
    public boolean isThrottled() {
        return statusCode == 429;
    }

    public GrowwAuthException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.retryAfterMillis = 0;
    }
}
