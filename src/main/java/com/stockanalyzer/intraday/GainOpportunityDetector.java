package com.stockanalyzer.intraday;

import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.GainOpportunity;

import java.util.List;

/**
 * Finds the windows during one session in which a gain was available.
 *
 * <p>Implementations are pure: same candles in, same opportunities out, no I/O.
 * {@link #version()} is stored with every result, so changing the rules later
 * writes a parallel, comparable series rather than rewriting history.
 */
public interface GainOpportunityDetector {

    List<GainOpportunity> detect(List<Candle> sessionCandles);

    String version();
}
