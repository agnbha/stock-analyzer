package com.stockanalyzer.store;

import com.stockanalyzer.model.DailyGainSummary;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface TradingDayRepository {

    /** Inserts or replaces the session row, returning its id. Safe to re-run for the same day. */
    long upsert(long instrumentId, DailyGainSummary summary, String source);

    Optional<Long> findId(long instrumentId, LocalDate sessionDate, int intervalMinutes);

    /** The session dates already stored - the {@code have} half of the backfill set difference. */
    Set<LocalDate> storedSessionDates(long instrumentId, int intervalMinutes, LocalDate from, LocalDate to);

    Optional<DailyGainSummary> find(long instrumentId, LocalDate sessionDate, int intervalMinutes);

    List<DailyGainSummary> findRange(long instrumentId, int intervalMinutes, LocalDate from, LocalDate to);

    /** Close of the most recent session strictly before {@code sessionDate}, for day-change. */
    Optional<Double> previousClose(long instrumentId, LocalDate sessionDate, int intervalMinutes);

    /** Last stored close on or before {@code date}, used to mark open positions to market. */
    Optional<Double> latestCloseOnOrBefore(long instrumentId, LocalDate date);

    int countSessions(LocalDate from, LocalDate to);
}
