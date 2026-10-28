package com.stockanalyzer.canslim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketDirectionAnalyzerTest {

    private final MarketDirectionAnalyzer analyzer = new MarketDirectionAnalyzer(CanSlimCriteria.defaults());

    @Test
    @DisplayName("an index above a rising 50-day average with no distribution is a confirmed uptrend")
    void confirmedUptrend() {
        MarketAssessment m = analyzer.assess("IDX", TestBars.from(100).trend(130, 80).build());

        assertEquals(MarketState.CONFIRMED_UPTREND, m.state());
        assertEquals(0, m.distributionDays());
    }

    @Test
    @DisplayName("four distribution days put the uptrend under pressure; six end it")
    void distributionDays() {
        TestBars bars = TestBars.from(100).trend(130, 80);
        // A 0.5% drop on volume above the day before, then a recovery on light volume.
        for (int i = 0; i < 4; i++) {
            bars.add(129.35, 1500).add(130, 1000);
        }
        assertEquals(MarketState.UPTREND_UNDER_PRESSURE, analyzer.assess("IDX", bars.build()).state());

        bars.add(129.35, 1500).add(130, 1000).add(129.35, 1500).add(130, 1000);
        MarketAssessment m = analyzer.assess("IDX", bars.build());
        assertEquals(6, m.distributionDays());
        assertEquals(MarketState.CORRECTION, m.state());
    }

    @Test
    @DisplayName("below a falling 50-day average is a correction")
    void fallingMarket() {
        assertEquals(MarketState.CORRECTION,
                analyzer.assess("IDX", TestBars.from(130).trend(100, 80).build()).state());
    }

    @Test
    @DisplayName("too little history is unknown, not a guess")
    void unknown() {
        assertEquals(MarketState.UNKNOWN, analyzer.assess("IDX", TestBars.from(100).trend(110, 30).build()).state());
    }
}
