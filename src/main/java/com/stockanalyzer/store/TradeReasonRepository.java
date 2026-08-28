package com.stockanalyzer.store;

import java.time.LocalDate;
import java.util.List;

/**
 * Why each trade was taken. Populated from what you typed, from the events that
 * had just fired, and from the alerts that preceded the fill - so the frequency
 * of each reason can be counted, and its P&L measured.
 */
public interface TradeReasonRepository {

    void upsertAll(List<TradeReason> reasons);

    List<TradeReason> findBetween(LocalDate from, LocalDate to);

    void deleteForTrades(List<Long> tradeIds);

    record TradeReason(long tradeId, String reasonCode, Source source, String detail) {

        public enum Source { MANUAL, EVENT, ALERT, MODEL, UNEXPLAINED }
    }
}
