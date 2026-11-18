package com.stockanalyzer.markets;

/** A market series could not be read: the source was unreachable, refused, or sent something that is not the series. */
public final class MarketDataException extends RuntimeException {

    public MarketDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
