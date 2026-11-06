package com.stockanalyzer.canslim;

import com.stockanalyzer.store.CompanyProfileRepository;
import com.stockanalyzer.store.InstrumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Keeps the company profiles the Candles and Calls page shows up to date from screener.in.
 *
 * <p>A page is read again when its copy is older than the refresh period, never-read companies first and
 * then the oldest, and at most a page budget a run: screener.in answers a burst of quick reads with 429, so
 * the pages are read one at a time (see {@link ScreenerCompanyClient}) and a nightly run takes a few, which
 * turns a week's worth of companies over a week. Anything left over is simply picked up next time.
 */
public final class CompanyProfileService {

    private static final Logger log = LoggerFactory.getLogger(CompanyProfileService.class);

    private final CompanyFundamentalsClient client;
    private final CompanyProfileRepository profiles;
    private final InstrumentRepository instruments;
    private final Clock clock;
    private final String exchange;
    private final String segment;

    public CompanyProfileService(CompanyFundamentalsClient client, CompanyProfileRepository profiles,
                                 InstrumentRepository instruments, Clock clock, String exchange, String segment) {
        this.client = client;
        this.profiles = profiles;
        this.instruments = instruments;
        this.clock = clock;
        this.exchange = exchange;
        this.segment = segment;
    }

    /**
     * @param fetched        companies whose page was read and stored
     * @param withoutProfile companies whose page was read and had nothing on them (an ETF)
     * @param notAttempted   companies that were due but over the page budget, or after a stop
     * @param stoppedBy      why the run ended early (a rate limit, an outage), or null
     */
    public record Result(int fetched, int withoutProfile, int notAttempted, String stoppedBy) {
    }

    /**
     * @param maxPages        the most pages to read this run; zero or less means every company that is due
     * @param staleAfterSeconds how old a copy may be before it is read again
     * @param force           read every company named, however fresh its copy
     */
    public Result refresh(List<String> symbols, int maxPages, long staleAfterSeconds, boolean force) {
        long now = clock.instant().getEpochSecond();
        record Due(String symbol, long instrumentId, long fetchedAt) {
        }
        List<Due> due = new ArrayList<>();
        for (String symbol : symbols) {
            long id = instruments.findOrCreate(symbol, exchange, segment);
            long fetchedAt = profiles.fetchedAt(id).orElse(0L);
            if (force || fetchedAt == 0 || now - fetchedAt >= staleAfterSeconds) {
                due.add(new Due(symbol, id, fetchedAt));
            }
        }
        due.sort(Comparator.comparingLong(Due::fetchedAt).thenComparing(Due::symbol));

        int budget = maxPages <= 0 ? due.size() : Math.min(maxPages, due.size());
        int fetched = 0;
        int empty = 0;
        int attempted = 0;
        String stoppedBy = null;
        for (Due company : due.subList(0, budget)) {
            try {
                Optional<CompanyProfile> profile = client.fetchProfile(company.symbol());
                if (profile.isPresent()) {
                    profiles.save(company.instrumentId(), profile.get(), now);
                    fetched++;
                } else {
                    profiles.saveEmpty(company.instrumentId(), now);
                    empty++;
                }
                attempted++;
            } catch (ScreenerException e) {
                stoppedBy = e.getMessage();
                log.warn("Company profiles stopped at {}: {}", company.symbol(), stoppedBy);
                break;
            }
        }
        return new Result(fetched, empty, due.size() - attempted, stoppedBy);
    }
}
