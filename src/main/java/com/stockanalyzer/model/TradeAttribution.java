package com.stockanalyzer.model;

/**
 * Links a trade you actually made to the opportunity that was available and the
 * alert (if any) that preceded it. Capture ratio measures improvement far
 * better than P&L, which is dominated by market direction.
 */
public record TradeAttribution(long tradeId,
                               Long gainOpportunityId,
                               Long alertLogId,
                               Integer entryLagMinutes,
                               Double capturePct) {
}
