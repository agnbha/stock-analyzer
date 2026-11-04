package com.stockanalyzer.live;

import com.stockanalyzer.intraday.DailyIngestionService;
import com.stockanalyzer.intraday.IngestionReport;
import com.stockanalyzer.intraday.TradingCalendar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Makes sure the session before today is stored before today's monitoring starts.
 *
 * <p>Yesterday's close is what today is measured against: the baseline on the Candles and Calls
 * chart, the gap event, the prior high and low the event detector compares with. The evening batch
 * normally stores it, but a laptop that was asleep at 18:30, or a run that failed, leaves a hole
 * that otherwise stays until someone notices.
 *
 * <p>It goes through the ordinary gap-filling ingestion, so a session that is already there costs a
 * database lookup and no request. Anything that goes wrong is logged and swallowed: a day's
 * monitoring is worth more than a missing baseline.
 */
public final class PriorSessionBackfill {

    private static final Logger log = LoggerFactory.getLogger(PriorSessionBackfill.class);

    private final DailyIngestionService ingestion;
    private final TradingCalendar calendar;

    public PriorSessionBackfill(DailyIngestionService ingestion, TradingCalendar calendar) {
        this.ingestion = ingestion;
        this.calendar = calendar;
    }

    /**
     * @return what was ingested, or empty when {@code sessionDate} is not a trading day or the check
     *         itself could not run
     */
    public Optional<IngestionReport> ensure(LocalDate sessionDate, List<String> symbols, String exchange,
                                            String segment, int intervalMinutes) {
        try {
            if (!calendar.isTradingDay(sessionDate)) {
                return Optional.empty();
            }
            LocalDate previous = calendar.previousTradingDay(sessionDate);
            IngestionReport report = ingestion.ingest(symbols, exchange, segment, previous, previous,
                    intervalMinutes, "prior-session");
            if (report.symbolsRequested() > 0) {
                log.info("The previous session, {}, was missing for {} of {} symbols: stored it for {}",
                        previous, report.symbolsRequested(), symbols.size(), report.succeeded());
                report.failures().forEach(failure -> log.warn("Could not backfill {} for {}: {}", previous,
                        failure.symbol(), failure.error()));
            } else {
                log.debug("The previous session, {}, is already stored for all {} symbols", previous,
                        symbols.size());
            }
            return Optional.of(report);
        } catch (RuntimeException e) {
            log.warn("Could not check that the previous session is stored: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
