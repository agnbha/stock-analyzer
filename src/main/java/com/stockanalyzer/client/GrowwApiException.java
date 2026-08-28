package com.stockanalyzer.client;

public class GrowwApiException extends RuntimeException {

    public GrowwApiException(String message) {
        super(message);
    }

    public GrowwApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
