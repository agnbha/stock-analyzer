package com.stockanalyzer.auth;

public class GrowwAuthException extends RuntimeException {

    public GrowwAuthException(String message) {
        super(message);
    }

    public GrowwAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
