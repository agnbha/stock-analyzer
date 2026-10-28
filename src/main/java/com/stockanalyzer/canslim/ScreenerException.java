package com.stockanalyzer.canslim;

/**
 * A screener request that could not produce results. {@link #needsLogin()}
 * separates the failure only a person can fix - a missing or expired session -
 * from everything else, so the message can say what to do about it.
 */
public final class ScreenerException extends RuntimeException {

    private final boolean needsLogin;

    public ScreenerException(String message, boolean needsLogin) {
        super(message);
        this.needsLogin = needsLogin;
    }

    public ScreenerException(String message, Throwable cause) {
        super(message, cause);
        this.needsLogin = false;
    }

    public boolean needsLogin() {
        return needsLogin;
    }
}
