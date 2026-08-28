package com.stockanalyzer.features;

import com.stockanalyzer.model.MarketEvent;
import com.stockanalyzer.model.TradingSession;

import java.util.List;

/**
 * Finds named, human-legible things that happened during a session. Events are
 * model features, alert reasons and a standalone "what happened today" report,
 * which is why they are stored rather than recomputed each time.
 */
public interface EventDetector {

    List<MarketEvent> detect(TradingSession session, PriorSessionContext context);

    String version();

    /** What the detector needs to know about the day before; all fields may be null. */
    record PriorSessionContext(Double priorClose, Double priorHigh, Double priorLow,
                               TimeOfDayVolumeProfile volumeProfile) {

        public static PriorSessionContext none() {
            return new PriorSessionContext(null, null, null, TimeOfDayVolumeProfile.empty());
        }
    }
}
