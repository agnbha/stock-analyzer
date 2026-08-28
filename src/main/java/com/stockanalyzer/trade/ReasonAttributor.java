package com.stockanalyzer.trade;

import com.stockanalyzer.model.Alert;
import com.stockanalyzer.model.MarketEvent;
import com.stockanalyzer.model.Trade;
import com.stockanalyzer.store.AlertRepository;
import com.stockanalyzer.store.InstrumentRepository;
import com.stockanalyzer.store.MarketEventRepository;
import com.stockanalyzer.store.TradeReasonRepository;
import com.stockanalyzer.store.TradeReasonRepository.TradeReason;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Works out why each trade was taken.
 *
 * <p>A note you typed is taken at face value. Otherwise the trade is matched
 * against what had just happened: events detected for that symbol shortly
 * before the fill, and alerts that fired for it. A trade with nothing behind it
 * is recorded as UNEXPLAINED rather than left out - "I do not know why I took
 * this one" is itself a frequency worth seeing on the dashboard.
 */
public final class ReasonAttributor {

    private final MarketEventRepository marketEventRepository;
    private final AlertRepository alertRepository;
    private final InstrumentRepository instrumentRepository;
    private final String exchange;
    private final String segment;
    private final int lookbackMinutes;

    public ReasonAttributor(MarketEventRepository marketEventRepository,
                            AlertRepository alertRepository,
                            InstrumentRepository instrumentRepository,
                            String exchange,
                            String segment,
                            int lookbackMinutes) {
        this.marketEventRepository = marketEventRepository;
        this.alertRepository = alertRepository;
        this.instrumentRepository = instrumentRepository;
        this.exchange = exchange;
        this.segment = segment;
        this.lookbackMinutes = lookbackMinutes;
    }

    public List<TradeReason> attribute(List<Trade> trades) {
        List<TradeReason> reasons = new ArrayList<>();
        for (Trade trade : trades) {
            List<TradeReason> forTrade = new ArrayList<>();

            if (trade.notes() != null && !trade.notes().isBlank()) {
                forTrade.add(new TradeReason(trade.id(), normalise(trade.notes()),
                        TradeReason.Source.MANUAL, trade.notes()));
            }

            long instrumentId = instrumentRepository.findOrCreate(trade.symbol(), exchange, segment);
            long window = lookbackMinutes * 60L;
            for (MarketEvent event : marketEventRepository.find(instrumentId, trade.sessionDate())) {
                long gap = trade.executedTs() - event.tsEpoch();
                if (gap >= 0 && gap <= window) {
                    forTrade.add(new TradeReason(trade.id(), event.type().name(), TradeReason.Source.EVENT,
                            String.format(Locale.ROOT, "%d min before the fill, strength %.2f",
                                    gap / 60, event.strength())));
                }
            }

            for (Alert alert : alertRepository.firedOn(trade.sessionDate())) {
                if (!trade.symbol().equals(alert.symbol())) {
                    continue;
                }
                long gap = trade.executedTs() - alert.firedAtEpoch();
                if (gap >= 0 && gap <= window) {
                    forTrade.add(new TradeReason(trade.id(), normalise(alert.rule()),
                            alert.rule().startsWith("model.") ? TradeReason.Source.MODEL : TradeReason.Source.ALERT,
                            alert.message()));
                }
            }

            if (forTrade.isEmpty()) {
                forTrade.add(new TradeReason(trade.id(), "UNEXPLAINED", TradeReason.Source.UNEXPLAINED,
                        "no note, event or alert within " + lookbackMinutes + " minutes"));
            }
            reasons.addAll(forTrade);
        }
        return reasons;
    }

    /** Free text becomes a countable code: "VWAP reclaim, strong volume" -> VWAP_RECLAIM_STRONG_VOLUME. */
    private static String normalise(String text) {
        String code = text.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return code.length() > 40 ? code.substring(0, 40) : code;
    }
}
