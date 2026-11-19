package com.stockanalyzer.markets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The series the two pages read, and how far back each is read again. */
class MarketSourcesTest {

    private final MarketSources sources = new MarketSources(null, null, null, null, null, null);

    @Test
    @DisplayName("the institutional series, named as the page's queries read them")
    void institutionalSeries() {
        assertEquals(List.of("FPI_EQUITY", "FPI_EQUITY_EXCHANGE", "FPI_EQUITY_PRIMARY", "FPI_DEBT", "FPI_TOTAL",
                "FII_CASH_BUY", "FII_CASH_SELL", "FII_CASH_NET", "DII_CASH_BUY", "DII_CASH_SELL", "DII_CASH_NET",
                "IDX_FUT_CLIENT_LONG", "IDX_FUT_CLIENT_SHORT", "IDX_FUT_DII_LONG", "IDX_FUT_DII_SHORT",
                "IDX_FUT_FII_LONG", "IDX_FUT_FII_SHORT", "IDX_FUT_PRO_LONG", "IDX_FUT_PRO_SHORT"),
                MarketSources.INSTITUTIONAL);
        assertTrue(MarketSources.ALL.containsAll(MarketSources.BONDS_AND_COMMODITIES));
        assertTrue(MarketSources.ALL.containsAll(MarketSources.INSTITUTIONAL));
        assertEquals(MarketSources.BONDS_AND_COMMODITIES.size() + MarketSources.INSTITUTIONAL.size(),
                MarketSources.ALL.size());
    }

    @Test
    @DisplayName("revisable sources are read a while back; final and latest-only ones from the newest day held")
    void rereadDays() {
        assertEquals(MarketSeriesService.REREAD_DAYS, sources.rereadDays("US10Y"));
        assertEquals(MarketSeriesService.REREAD_DAYS, sources.rereadDays("BRENT"));
        assertEquals(0, sources.rereadDays("MCX_GOLD"));
        assertEquals(7, sources.rereadDays("FPI_EQUITY"), "a month a request, so a week back costs nothing");
        assertEquals(0, sources.rereadDays("DII_CASH_NET"), "only ever the latest day");
        assertEquals(0, sources.rereadDays("IDX_FUT_FII_LONG"), "a file a day, final once out");
    }

    @Test
    @DisplayName("a series nobody publishes is an error")
    void unknownSeries() {
        assertThrows(MarketDataException.class, () -> sources.apply("FPI_SOMETHING", LocalDate.of(2026, 11, 1)));
        assertThrows(MarketDataException.class, () -> sources.apply("NIFTY", LocalDate.of(2026, 11, 1)));
    }
}
