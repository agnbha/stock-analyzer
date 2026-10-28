package com.stockanalyzer.canslim;

import java.util.Map;

/**
 * What the whole-market part of a scan did.
 *
 * @param listed                companies in the instrument list that the scan was asked to cover
 * @param failed                of those, how many could not be priced
 * @param ranked                how many were fit to be ranked, and so scored
 * @param unranked              priced but left out of the ranking, by reason
 * @param fundamentalsRequested how many ranked at or above the RS bar and so had their figures looked up
 */
public record MarketSummary(int listed, int failed, int ranked, Map<String, Integer> unranked,
                            int fundamentalsRequested) {
}
