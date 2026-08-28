package com.stockanalyzer.model;

/** An unclosed position, carried forward until a sell consumes it. */
public record OpenPosition(String symbol, Product product, int quantity, double avgCost, long openedTs) {
}
