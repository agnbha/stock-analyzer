package com.stockanalyzer.canslim;

import com.stockanalyzer.model.Instrument;
import com.stockanalyzer.store.CompanyProfileRepository;
import com.stockanalyzer.store.InstrumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Keeps the company profiles the Candles and Calls page shows up to date from screener.in.
 *
 * <p>A page is read again when its copy is older than the refresh period, never-read companies first and
 * then the oldest, and at most a page budget a run: screener.in answers a burst of quick reads with 429, so
 * the pages are read one at a time (see {@link ScreenerCompanyClient}) and a nightly run takes a few, which
 * turns a week's worth of companies over a week. Anything left over is simply picked up next time.
 *
 * <p>Two tiers. The symbols in {@code symbols.txt} are the ones watched closely, and are kept current every week
 * ({@link #refresh}). Every other instrument the database knows, the whole market's daily bars and any stock
 * traded outside the list, is read on a much slower round, a few pages a night so that a throttle only delays
 * it ({@link #refreshUntracked}); a stock you have traded comes ahead of the rest, because those are the ones
 * opened on the page.
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
        List<Candidate> candidates = new ArrayList<>();
        for (String symbol : symbols) {
            candidates.add(new Candidate(symbol, instruments.findOrCreate(symbol, exchange, segment)));
        }
        return read(due(candidates, staleAfterSeconds, force, now), maxPages, now);
    }

    /**
     * The slow round over everything {@code symbols.txt} does not name: every instrument of this exchange and
     * segment the database holds, read when its copy is older than {@code staleAfterSeconds} (months, not days).
     * Stocks in {@code traded} come first, then never-read ones, then the oldest, all within one page budget.
     *
     * @param tracked         the symbols that have their own, faster round; left alone here
     * @param traded          symbols with a trade on record, read ahead of the rest
     * @param maxPages        the most pages to read this run; zero or less means every instrument that is due
     */
    public Result refreshUntracked(Collection<String> tracked, Collection<String> traded, int maxPages,
                                   long staleAfterSeconds) {
        long now = clock.instant().getEpochSecond();
        Set<String> skip = upperCase(tracked);
        Set<String> mine = upperCase(traded);
        List<Candidate> ahead = new ArrayList<>();
        List<Candidate> rest = new ArrayList<>();
        for (Instrument instrument : instruments.findAll()) {
            if (!exchange.equals(instrument.exchange()) || !segment.equals(instrument.segment())
                    || skip.contains(instrument.symbol().toUpperCase(Locale.ROOT))) {
                continue;
            }
            Candidate candidate = new Candidate(instrument.symbol(), instrument.id());
            (mine.contains(instrument.symbol().toUpperCase(Locale.ROOT)) ? ahead : rest).add(candidate);
        }
        List<Due> ordered = new ArrayList<>(due(ahead, staleAfterSeconds, false, now));
        ordered.addAll(due(rest, staleAfterSeconds, false, now));
        return read(ordered, maxPages, now);
    }

    private record Candidate(String symbol, long instrumentId) {
    }

    private record Due(String symbol, long instrumentId, long fetchedAt) {
    }

    /** The candidates whose copy is stale, never-read ones first and then the oldest. */
    private List<Due> due(List<Candidate> candidates, long staleAfterSeconds, boolean force, long now) {
        List<Due> due = new ArrayList<>();
        for (Candidate candidate : candidates) {
            long fetchedAt = profiles.fetchedAt(candidate.instrumentId()).orElse(0L);
            if (force || fetchedAt == 0 || now - fetchedAt >= staleAfterSeconds) {
                due.add(new Due(candidate.symbol(), candidate.instrumentId(), fetchedAt));
            }
        }
        due.sort(Comparator.comparingLong(Due::fetchedAt).thenComparing(Due::symbol));
        return due;
    }

    /** Reads the first {@code maxPages} of an ordered list one at a time, until a refusal stops the run. */
    private Result read(List<Due> due, int maxPages, long now) {
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

    private static Set<String> upperCase(Collection<String> symbols) {
        Set<String> upper = new HashSet<>();
        symbols.forEach(symbol -> upper.add(symbol.toUpperCase(Locale.ROOT)));
        return upper;
    }
}
