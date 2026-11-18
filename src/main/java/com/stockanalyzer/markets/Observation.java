package com.stockanalyzer.markets;

import java.time.LocalDate;

/** One day's reading of a market series: a yield in percent, or a price in the series' currency. */
public record Observation(LocalDate date, double value) {
}
