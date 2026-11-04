package com.stockanalyzer.client;

/**
 * Groww answered that it does not know the trading symbol it was asked about: HTTP 400, code GA001,
 * "Please provide correct value of trading symbol". Asking again gets the same answer, so a caller
 * that scans thousands of listings can remember the symbol and stop asking.
 *
 * <p>Other 400s are not this: a malformed date range or a missing parameter is the caller's fault,
 * and the symbol is not to blame for it.
 */
public final class GrowwSymbolRejectedException extends GrowwApiException {

    public GrowwSymbolRejectedException(String message, int statusCode) {
        super(message, statusCode);
    }
}
