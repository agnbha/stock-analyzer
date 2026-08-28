package com.stockanalyzer.model;

/** An instrument as stored locally; {@code id} is the local surrogate key. */
public record Instrument(long id, String symbol, String exchange, String segment, String name) {
}
