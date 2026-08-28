package com.stockanalyzer.trade;

import com.stockanalyzer.model.Side;
import com.stockanalyzer.model.Trade;
import com.stockanalyzer.model.TradeAttribution;
import com.stockanalyzer.store.GainOpportunityRepository;
import com.stockanalyzer.store.OpportunityRow;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Compares what you actually did against what was available.
 *
 * <p>Part 1 already knows the best windows of every session, so the journal can
 * answer a question no broker statement can: of the gain that was there, how
 * much did you take, and how late were you? Aggregated over months, capture
 * ratio and entry lag measure improvement far better than P&L, which is
 * dominated by market direction.
 */
public final class CaptureAnalyzer {

    private final GainOpportunityRepository gainOpportunityRepository;
    private final String detectorVersion;

    public CaptureAnalyzer(GainOpportunityRepository gainOpportunityRepository, String detectorVersion) {
        this.gainOpportunityRepository = gainOpportunityRepository;
        this.detectorVersion = detectorVersion;
    }

    public List<TradeAttribution> analyse(List<Trade> trades, LocalDate from, LocalDate to) {
        Map<String, List<OpportunityRow>> bySymbolDate = gainOpportunityRepository
                .findRange(from, to, detectorVersion, null).stream()
                .collect(Collectors.groupingBy(row -> key(row.symbol(), row.sessionDate())));

        List<TradeAttribution> attributions = new ArrayList<>();
        for (Trade trade : trades) {
            if (trade.side() != Side.BUY) {
                continue;
            }
            List<OpportunityRow> candidates = bySymbolDate.get(key(trade.symbol(), trade.sessionDate()));
            if (candidates == null || candidates.isEmpty()) {
                continue;
            }
            OpportunityRow nearest = candidates.stream()
                    .min(Comparator.comparingLong(
                            row -> Math.abs(row.opportunity().entryTs() - trade.executedTs())))
                    .orElseThrow();
            double bestAvailable = candidates.stream()
                    .mapToDouble(row -> row.opportunity().gainPct())
                    .max().orElse(0);

            int entryLagMinutes = (int) ((trade.executedTs() - nearest.opportunity().entryTs()) / 60);
            Double capturePct = bestAvailable <= 0
                    ? null
                    : nearest.opportunity().gainPct() / bestAvailable * 100.0;

            attributions.add(new TradeAttribution(trade.id(), nearest.id(), null, entryLagMinutes, capturePct));
        }
        return attributions;
    }

    private static String key(String symbol, LocalDate date) {
        return symbol + "|" + date;
    }
}
