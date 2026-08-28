package com.stockanalyzer.intraday;

import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.DailyGainSummary;
import com.stockanalyzer.model.Instrument;
import com.stockanalyzer.model.TradingSession;
import com.stockanalyzer.store.CandleRepository;
import com.stockanalyzer.store.GainOpportunityRepository;
import com.stockanalyzer.store.InstrumentRepository;
import com.stockanalyzer.store.TradingDayRepository;
import com.stockanalyzer.util.MarketClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;

/**
 * Re-runs a detector over candles already stored, with no API calls at all.
 *
 * <p>Because the detector version is part of the opportunity key, a changed
 * detector writes a parallel series that can be compared against the old one
 * before anything is thrown away.
 */
public final class RecomputeService {

    private static final Logger log = LoggerFactory.getLogger(RecomputeService.class);

    private final InstrumentRepository instrumentRepository;
    private final TradingDayRepository tradingDayRepository;
    private final GainOpportunityRepository gainOpportunityRepository;
    private final CandleRepository candleRepository;
    private final DailySummaryBuilder summaryBuilder;
    private final MarketClock clock;

    public RecomputeService(InstrumentRepository instrumentRepository,
                            TradingDayRepository tradingDayRepository,
                            GainOpportunityRepository gainOpportunityRepository,
                            CandleRepository candleRepository,
                            DailySummaryBuilder summaryBuilder,
                            MarketClock clock) {
        this.instrumentRepository = instrumentRepository;
        this.tradingDayRepository = tradingDayRepository;
        this.gainOpportunityRepository = gainOpportunityRepository;
        this.candleRepository = candleRepository;
        this.summaryBuilder = summaryBuilder;
        this.clock = clock;
    }

    /** Returns the number of sessions recomputed. */
    public int recompute(LocalDate from, LocalDate to, int intervalMinutes) {
        int recomputed = 0;
        int skipped = 0;
        for (Instrument instrument : instrumentRepository.findAll()) {
            for (DailyGainSummary stored : tradingDayRepository.findRange(
                    instrument.id(), intervalMinutes, from, to)) {
                List<Candle> candles = candleRepository.find(instrument.id(), intervalMinutes,
                        clock.sessionOpenEpoch(stored.sessionDate()),
                        clock.sessionCloseEpoch(stored.sessionDate()));
                if (candles.isEmpty()) {
                    skipped++;
                    continue;
                }
                TradingSession session = new TradingSession(instrument.symbol(), instrument.exchange(),
                        instrument.segment(), stored.sessionDate(), intervalMinutes, candles);
                Double previousClose = tradingDayRepository
                        .previousClose(instrument.id(), stored.sessionDate(), intervalMinutes)
                        .orElse(null);
                DailyGainSummary summary = summaryBuilder.build(session, previousClose);
                long tradingDayId = tradingDayRepository.upsert(instrument.id(), summary, "recompute");
                gainOpportunityRepository.replace(tradingDayId, summaryBuilder.detectorVersion(),
                        summary.opportunities());
                recomputed++;
            }
        }
        if (skipped > 0) {
            log.warn("{} sessions had no stored candles and were skipped "
                    + "(intraday.store.raw.candles must be on to recompute without refetching)", skipped);
        }
        log.info("Recomputed {} sessions as {}", recomputed, summaryBuilder.detectorVersion());
        return recomputed;
    }
}
