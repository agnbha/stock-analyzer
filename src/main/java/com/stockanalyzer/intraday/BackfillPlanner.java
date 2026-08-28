package com.stockanalyzer.intraday;

import com.stockanalyzer.store.InstrumentRepository;
import com.stockanalyzer.store.TradingDayRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out what is missing rather than tracking a cursor:
 * {@code missing = tradingDays(from, to) - alreadyStored}.
 *
 * <p>That makes the nightly run and a multi-month backfill the same code path -
 * a daily run is just {@code from == to} - and it self-heals, because a symbol
 * that failed three days ago is simply still missing next time.
 */
public final class BackfillPlanner {

    private final TradingCalendar calendar;
    private final InstrumentRepository instrumentRepository;
    private final TradingDayRepository tradingDayRepository;

    public BackfillPlanner(TradingCalendar calendar,
                           InstrumentRepository instrumentRepository,
                           TradingDayRepository tradingDayRepository) {
        this.calendar = calendar;
        this.instrumentRepository = instrumentRepository;
        this.tradingDayRepository = tradingDayRepository;
    }

    /** Missing sessions per symbol, in symbol order, each list ordered oldest first. */
    public Map<String, List<LocalDate>> plan(List<String> symbols, String exchange, String segment,
                                             LocalDate from, LocalDate to, int intervalMinutes) {
        List<LocalDate> wanted = calendar.tradingDaysBetween(from, to);
        Map<String, List<LocalDate>> missingBySymbol = new LinkedHashMap<>();
        for (String symbol : symbols) {
            long instrumentId = instrumentRepository.findOrCreate(symbol, exchange, segment);
            Set<LocalDate> have = tradingDayRepository.storedSessionDates(instrumentId, intervalMinutes, from, to);
            List<LocalDate> missing = new ArrayList<>();
            for (LocalDate date : wanted) {
                if (!have.contains(date)) {
                    missing.add(date);
                }
            }
            if (!missing.isEmpty()) {
                missingBySymbol.put(symbol, missing);
            }
        }
        return missingBySymbol;
    }
}
