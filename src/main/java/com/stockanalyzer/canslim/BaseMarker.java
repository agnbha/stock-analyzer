package com.stockanalyzer.canslim;

import com.stockanalyzer.store.CanSlimRepository;
import com.stockanalyzer.store.DailyBarRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out the bases of stocks from the daily bars held and stores them for the boxes on CAN SLIM's chart. Needs no
 * broker and no screener: the scan runs it for the stocks it scores, and {@code CanSlimMain bases} runs it alone.
 */
public final class BaseMarker {

    private static final Logger log = LoggerFactory.getLogger(BaseMarker.class);

    private final DailyBarRepository dailyBars;
    private final CanSlimRepository runs;
    private final PriceActionAnalyzer analyzer;

    public BaseMarker(DailyBarRepository dailyBars, CanSlimRepository runs, CanSlimCriteria criteria) {
        this.dailyBars = dailyBars;
        this.runs = runs;
        this.analyzer = new PriceActionAnalyzer(criteria);
    }

    /**
     * Marks the bases of each of these stocks and stores them. A stock whose bars cannot be read is left as it was: the
     * boxes are not worth failing a scan for.
     *
     * @return how many bases were stored
     */
    public int refresh(Collection<Long> instrumentIds) {
        Map<Long, List<BaseMark>> bases = new LinkedHashMap<>();
        int stored = 0;
        for (long instrumentId : instrumentIds) {
            try {
                List<BaseMark> marks = analyzer.basesIn(dailyBars.find(instrumentId));
                bases.put(instrumentId, marks);
                stored += marks.size();
            } catch (RuntimeException e) {
                log.warn("Could not mark the bases of instrument {}: {}", instrumentId, e.getMessage());
            }
        }
        try {
            runs.saveBases(bases);
        } catch (RuntimeException e) {
            log.warn("Could not store the bases: {}", e.getMessage());
            return 0;
        }
        return stored;
    }

    /** The bases of every stock the latest scan scored. */
    public int refreshLatest() {
        return refresh(runs.latestScoredInstruments());
    }
}
