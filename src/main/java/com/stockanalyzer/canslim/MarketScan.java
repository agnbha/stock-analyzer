package com.stockanalyzer.canslim;

import java.time.LocalDate;
import java.util.List;

/**
 * The whole-market part of a scan: every listed symbol to price, and the rules for which of them
 * are fit to be ranked.
 *
 * <p>A percentile rank means something only against stocks that actually trade. Thousands of
 * NSE and BSE listings do not: a suspended company's last print, a ten-rupee stock that changes
 * hands a few times a day. They are priced, so nothing is silently missing, but they are left out
 * of the RS field - otherwise every real stock would look better for having beaten them.
 *
 * @param minSessions       fewest daily bars to be ranked; the RS return reaches back a quarter at least
 * @param minPrice          lowest last close, in rupees
 * @param minTurnover       lowest average daily value traded (close x volume) over the last 50 sessions, in rupees
 * @param maxStaleDays      how long ago the last bar may be before the stock counts as suspended
 * @param fundamentalsMinRs the RS rating from which screener.in is asked for a company's figures: a stock
 *                          below the L bar cannot become a buy, so its earnings are not worth a page load
 * @param keepRuns          how many scans of market-wide scores to keep; older ones are dropped
 * @param minMarketCapCr    a listing is selected only with a market capitalisation above this, in rupees
 *                          crore. No bulk source of market caps exists, so the figure comes from each
 *                          company's screener.in page, read for the stocks that rank high enough to be a
 *                          buy; a listing whose page has not been read yet is not selected, and one found
 *                          at or below the floor is remembered and dropped from the ranking
 */
public record MarketScan(List<MarketListing> listings, int minSessions, double minPrice, double minTurnover,
                         int maxStaleDays, int fundamentalsMinRs, int keepRuns, double minMarketCapCr) {

    /** True when the figure is known and does not clear the floor. An unknown one is not "below". */
    boolean belowFloor(Double marketCapCr) {
        return marketCapCr != null && marketCapCr <= minMarketCapCr;
    }

    /** The reason given for a listing left out for its size, with the floor in it so the report says what it was. */
    String belowFloorReason() {
        return String.format(java.util.Locale.ROOT, "market cap at or under %,.0f Cr", minMarketCapCr);
    }

    /** Why a priced symbol is not ranked, or null when it is fit to be. */
    String unrankedReason(int sessions, double lastClose, double avgTurnover, LocalDate lastBar, LocalDate today) {
        if (sessions < minSessions) {
            return "short history";
        }
        if (lastBar.isBefore(today.minusDays(maxStaleDays))) {
            return "stale prices";
        }
        if (lastClose < minPrice) {
            return "low price";
        }
        if (avgTurnover < minTurnover) {
            return "illiquid";
        }
        return null;
    }
}
