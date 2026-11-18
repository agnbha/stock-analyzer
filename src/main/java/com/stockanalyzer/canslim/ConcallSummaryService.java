package com.stockanalyzer.canslim;

import com.stockanalyzer.store.CompanyProfileRepository;
import com.stockanalyzer.store.ConcallSummaryRepository;
import com.stockanalyzer.store.InstrumentRepository;

import java.time.Clock;
import java.time.YearMonth;
import java.util.Optional;
import java.util.function.Function;

/**
 * Reads screener.in's AI summary of a company's earnings call into the Company Details page, on request only: the
 * account may open ten summaries in thirty days, and the same allowance serves its owner's own reading on the site.
 * The company's public page is read first, so the call is the latest one listed and the page's filings are current;
 * a summary already held is not read again unless asked, since a call's summary does not change.
 */
public final class ConcallSummaryService {

    private final Function<String, Optional<CompanyProfile>> pages;
    private final Function<String, String> summaries;
    private final CompanyProfileRepository profiles;
    private final ConcallSummaryRepository held;
    private final InstrumentRepository instruments;
    private final Clock clock;
    private final String exchange;
    private final String segment;

    /**
     * @param pages     reads a company's public page ({@link ScreenerCompanyClient#fetchProfile})
     * @param summaries reads a summary from its address, signed in ({@link ScreenerCompanyClient#concallSummary})
     */
    public ConcallSummaryService(Function<String, Optional<CompanyProfile>> pages, Function<String, String> summaries,
                                 CompanyProfileRepository profiles, ConcallSummaryRepository held,
                                 InstrumentRepository instruments, Clock clock, String exchange, String segment) {
        this.pages = pages;
        this.summaries = summaries;
        this.profiles = profiles;
        this.held = held;
        this.instruments = instruments;
        this.clock = clock;
        this.exchange = exchange;
        this.segment = segment;
    }

    /**
     * @param lines     the summary's lines now held
     * @param heldSince when it was read, if it was already held and so not read again; otherwise null
     * @param viewed    the account's summaries opened in the last thirty days, as screener.in said; null if not read
     * @param allowance how many it may open in thirty days; null if not read
     */
    public record Result(String symbol, YearMonth call, int lines, Long heldSince, Integer viewed, Integer allowance) {
    }

    /**
     * @param call  the month of the call to summarise, or null for the latest one screener.in has a summary of
     * @param again read it even if it is held
     */
    public Result read(String symbol, YearMonth call, boolean again) {
        long now = clock.instant().getEpochSecond();
        long instrumentId = instruments.findOrCreate(symbol, exchange, segment);
        CompanyProfile profile = pages.apply(symbol).orElseThrow(() ->
                new ScreenerException("screener.in has no company page with figures for " + symbol, false));
        profiles.save(instrumentId, profile, now);
        CompanyProfile.Concall chosen = profile.concalls().stream()
                .filter(c -> c.summaryUrl() != null && (call == null || c.period().equals(call)))
                .findFirst()
                .orElseThrow(() -> new ScreenerException("screener.in has no AI summary of " + symbol + "'s "
                        + (call == null ? "listed calls" : call + " call"), false));
        Optional<Long> heldSince = held.fetchedAt(instrumentId, chosen.period());
        if (heldSince.isPresent() && !again) {
            return new Result(symbol, chosen.period(), -1, heldSince.get(), null, null);
        }
        ConcallSummary summary = ConcallSummary.parse(summaries.apply(chosen.summaryUrl()));
        if (summary.lines().isEmpty()) {
            throw new ScreenerException("screener.in's summary of " + symbol + "'s " + chosen.period()
                    + " call had no text", false);
        }
        held.save(instrumentId, chosen.period(), chosen.summaryUrl(), summary.lines(), now);
        return new Result(symbol, chosen.period(), summary.lines().size(), null, summary.viewed(), summary.allowance());
    }
}
