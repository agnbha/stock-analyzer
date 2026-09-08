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

    /**
     * 401/403: the credential or the session behind it is not acceptable.
     *
     * <p>Unlike a 429 this does not come right on its own - someone has to
     * re-approve the session or fix the credential. Retrying at poll frequency
     * cannot succeed, and only spends the token endpoint's quota until it
     * answers 429 as well, which is how one auth failure turns into an outage
     * that looks like rate limiting.
     */
    public boolean needsApproval() {
        return statusCode == 401 || statusCode == 403;
    }

    public GrowwAuthException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.retryAfterMillis = 0;
    }
}
