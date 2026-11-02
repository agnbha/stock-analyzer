package com.stockanalyzer.canslim;

import java.util.Map;

/**
 * What the whole-market part of a scan did.
 *
 * @param listed                companies in the instrument list that the scan was asked to cover
 * @param failed                of those, how many could not be priced
 * @param ranked                how many were in the RS field: priced, liquid, and not already known to be small
 * @param selected              how many were scored: confirmed above the market-cap floor from their company page
 * @param unchecked             ranked but neither confirmed nor ruled out - below the RS bar, so their page was
 *                              not read, or waiting for the scan's page budget
 * @param unranked              left out of the ranking, by reason (illiquid, stale, under the market-cap floor...)
 * @param fundamentalsRequested how many ranked at or above the RS bar and so had their company page asked for
 * @param minMarketCapCr        the market-cap floor in force, in rupees crore
 */
public record MarketSummary(int listed, int failed, int ranked, int selected, int unchecked,
                            Map<String, Integer> unranked, int fundamentalsRequested, double minMarketCapCr) {
}
