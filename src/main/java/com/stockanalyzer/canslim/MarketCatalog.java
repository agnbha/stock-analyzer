package com.stockanalyzer.canslim;

import java.util.List;

/** Every symbol a whole-market scan should look at. */
public interface MarketCatalog {

    List<MarketListing> listings();
}
