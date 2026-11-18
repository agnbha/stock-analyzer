package com.stockanalyzer.markets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The open oil-prices dataset's daily files, in their own layout. The prices are invented. */
class OilPricesClientTest {

    @Test
    @DisplayName("a trading day and its price a line, oldest first; a day without a price is left out")
    void prices() {
        assertEquals(List.of(new Observation(LocalDate.of(2026, 11, 10), 90.16),
                new Observation(LocalDate.of(2026, 11, 11), 92.14)), OilPricesClient.parse("brent", """
                Date,Price
                2026-11-09,
                2026-11-10,90.16\r
                2026-11-11,92.14
                """));
    }

    @Test
    @DisplayName("a page that is not the file, or a line that cannot be read, is refused rather than read as no prices")
    void notTheFile() {
        assertThrows(MarketDataException.class, () -> OilPricesClient.parse("wti", "404: Not Found"));
        assertThrows(MarketDataException.class, () -> OilPricesClient.parse("wti", "Date,Price\n2026-11-11,n/a\n"));
    }
}
