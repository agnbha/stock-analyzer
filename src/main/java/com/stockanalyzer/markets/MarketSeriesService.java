package com.stockanalyzer.markets;

import com.stockanalyzer.store.MarketSeriesRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Keeps the US Treasury yields and crude oil prices the page linked from CAN SLIM draws. Each evening every series is
 * read from a month before its newest reading, or over the whole history the first time: a source can revise a recent
 * reading, and an oil price arrives a week late.
 */
public final class MarketSeriesService {

    private static final Logger log = LoggerFactory.getLogger(MarketSeriesService.class);
    /** How far back a series already held is read again each time. */
    static final int REREAD_DAYS = 31;

    private final BiFunction<String, LocalDate, List<Observation>> source;
    private final MarketSeriesRepository repository;
    private final Clock clock;

    /** @param source reads a series' readings from a date on, oldest first ({@link MarketSources}) */
    public MarketSeriesService(BiFunction<String, LocalDate, List<Observation>> source,
                               MarketSeriesRepository repository, Clock clock) {
        this.source = source;
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * @param stored the readings received and stored this time, new or revised
     * @param latest the newest reading held after this run, if any
     * @param error  why the series could not be read, or null
     */
    public record Result(String series, int stored, LocalDate latest, String error) {
    }

    /**
     * Reads each series in turn. One that fails is reported and left as it was; the others are still read.
     *
     * @param historyDays how far back to start a series held for the first time
     */
    public List<Result> refresh(List<String> series, int historyDays) {
        LocalDate today = LocalDate.now(clock);
        long now = clock.instant().getEpochSecond();
        List<Result> results = new ArrayList<>();
        for (String id : series) {
            LocalDate from = repository.latest(id).map(latest -> latest.minusDays(REREAD_DAYS))
                    .orElse(today.minusDays(historyDays));
            try {
                List<Observation> observations = source.apply(id, from);
                repository.upsert(id, observations, now);
                results.add(new Result(id, observations.size(), repository.latest(id).orElse(null), null));
            } catch (MarketDataException e) {
                log.warn("Could not read {}: {}", id, e.getMessage());
                results.add(new Result(id, 0, repository.latest(id).orElse(null), e.getMessage()));
            }
        }
        return results;
    }
}
