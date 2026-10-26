package com.stockanalyzer.canslim;

import java.util.List;

/**
 * Runs a fundamentals query and returns the companies that pass it.
 *
 * <p>The query does the coarse fundamental filtering (the C, A and I of
 * CAN SLIM) on the provider's side, where the quarterly and annual numbers
 * live; everything price-based is evaluated locally from candles.
 */
public interface ScreenerClient {

    List<ScreenedStock> screen(String query);
}
