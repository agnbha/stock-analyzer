package com.stockanalyzer.client;

/**
 * A failed call to the Groww API. {@code statusCode} is 0 when the request never
 * got a response (a transport failure); otherwise it carries the HTTP status, so
 * the retry decorator can distinguish "try again" from "this will never work".
 */
public class GrowwApiException extends RuntimeException {

    private final int statusCode;
    private final long retryAfterMillis;

    public GrowwApiException(String message) {
        this(message, 0);
    }

    public GrowwApiException(String message, int statusCode) {
        this(message, statusCode, 0);
    }

    public GrowwApiException(String message, int statusCode, long retryAfterMillis) {
        super(message);
        this.statusCode = statusCode;
        this.retryAfterMillis = retryAfterMillis;
    }

    public GrowwApiException(String message, Throwable cause) {
        this(message, cause, 0);
    }

    /**
     * How long the server asked us to wait, from a {@code Retry-After} header;
     * 0 when it did not say. Worth more than any backoff we invent ourselves.
     */
    public long retryAfterMillis() {
        return retryAfterMillis;
    }

    public GrowwApiException(String message, Throwable cause, int statusCode) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryAfterMillis = 0;
    }

    public int statusCode() {
        return statusCode;
    }

    /** Rate limiting and server-side faults are worth another attempt; a 4xx is not. */
    public boolean isRetryable() {
        return statusCode == 0 || statusCode == 429 || statusCode >= 500;
    }
}
