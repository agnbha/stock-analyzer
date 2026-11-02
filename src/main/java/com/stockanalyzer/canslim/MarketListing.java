package com.stockanalyzer.canslim;

/**
 * One listed company in the whole-market scan.
 *
 * @param symbol      the trading symbol the candle API takes for {@code exchange}
 * @param exchange    NSE, or BSE for a company that has no NSE listing
 * @param isin        the company's equity ISIN, which is what recognises one company across both exchanges
 * @param series      the exchange's own group for it: EQ and BE on NSE, A and B on BSE, and so on
 * @param screenerKey what screener.in's company page is addressed by: the NSE symbol, or the BSE scrip code
 */
public record MarketListing(String symbol, String exchange, String name, String isin, String series,
                            String screenerKey) {
}
