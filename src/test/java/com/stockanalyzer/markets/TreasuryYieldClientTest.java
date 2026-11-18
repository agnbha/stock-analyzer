package com.stockanalyzer.markets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The US Treasury's daily par yield curve file, in its own layout. The rates are invented. */
class TreasuryYieldClientTest {

    private static final String YEAR = """
            Date,"1 Mo","1.5 Month","2 Mo","3 Mo","4 Mo","6 Mo","1 Yr","2 Yr","3 Yr","5 Yr","7 Yr","10 Yr","20 Yr","30 Yr"
            11/17/2026,4.09,4.24,4.26,4.33,4.32,4.41,4.60,4.80,4.84,4.90,4.99,5.10,5.50,5.46\r
            11/16/2026,4.10,4.19,4.27,4.34,4.33,4.42,4.62,,4.87,4.93,5.01,5.12,5.51,5.46
            11/13/2026,4.11,4.20,4.28,4.35,4.34,4.43,4.63,4.85,4.88,4.94,5.02,5.15,5.52,5.47
            """;

    @Test
    @DisplayName("one maturity's column, oldest first, dated from the file's month/day/year")
    void oneMaturity() {
        assertEquals(List.of(new Observation(LocalDate.of(2026, 11, 13), 5.15),
                new Observation(LocalDate.of(2026, 11, 16), 5.12),
                new Observation(LocalDate.of(2026, 11, 17), 5.10)), TreasuryYieldClient.parse("10 Yr", YEAR));
    }

    @Test
    @DisplayName("a blank rate is a day without one, not a zero")
    void blankRate() {
        List<Observation> twoYear = TreasuryYieldClient.parse("2 Yr", YEAR);
        assertEquals(List.of(LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 17)),
                twoYear.stream().map(Observation::date).toList());
    }

    @Test
    @DisplayName("a file without the maturity, such as an error page, is refused rather than read as no rates")
    void notTheFile() {
        assertThrows(MarketDataException.class, () -> TreasuryYieldClient.parse("10 Yr", "<html>Not found</html>"));
        assertThrows(MarketDataException.class,
                () -> TreasuryYieldClient.parse("10 Yr", "Date,\"1 Mo\"\n11/17/2026,4.09\n"));
    }
}
