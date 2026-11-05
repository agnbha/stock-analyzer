package com.stockanalyzer.watchlist;

import com.stockanalyzer.store.WatchlistPickRepository;
import com.stockanalyzer.store.WatchlistPickRepository.Pick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;

/**
 * Keeps the Watchlist honest: each evening's shortlist is written down, and the dashboard reads
 * how those picks did on the next session from the stored sessions.
 *
 * <p>The ranking is the one the page shows, over complete sessions only. A shortlist is stamped with
 * the session whose close fed it, and is for the session after.
 */
public final class WatchlistService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistService.class);

    private final WatchlistPickRepository picks;

    public WatchlistService(WatchlistPickRepository picks) {
        this.picks = picks;
    }

    /**
     * Writes the shortlist that the session of {@code asOf} produced, replacing any earlier one for that
     * evening (a re-run, or a session re-ingested since). When nothing has a score the old rows are left
     * alone and the result is empty.
     */
    public List<Pick> logEvening(LocalDate asOf, int top) {
        return log(asOf, top, WatchlistPickRepository.LIVE);
    }

    /**
     * Rebuilds shortlists for every stored session from {@code from} to {@code to}, so the scorecard has a
     * history on its first day. Evenings that were logged live are kept as they were: a replay must not
     * rewrite what the page actually showed.
     *
     * @return how many evenings were written
     */
    public int replay(LocalDate from, LocalDate to, int top) {
        int written = 0;
        for (LocalDate date : picks.sessionDates(from, to)) {
            if (picks.isLogged(date, WatchlistPickRepository.LIVE)) {
                continue;
            }
            if (!log(date, top, WatchlistPickRepository.REPLAY).isEmpty()) {
                written++;
            }
        }
        return written;
    }

    private List<Pick> log(LocalDate asOf, int top, String source) {
        List<Pick> shortlist = picks.ranking(asOf, top);
        if (shortlist.isEmpty()) {
            log.info("No symbol has a score for {} yet; shortlist not written", asOf);
            return shortlist;
        }
        picks.replace(asOf, shortlist, source);
        return shortlist;
    }
}
