package com.stockanalyzer.canslim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScreenerResultsParserTest {

    private static String fixture() throws IOException {
        try (InputStream in = ScreenerResultsParserTest.class.getResourceAsStream("/screener/results-page.html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("reads each company's NSE symbol and its ratios by column tooltip")
    void readsCompaniesAndRatios() throws IOException {
        ScreenerResultsParser.Page page = ScreenerResultsParser.parse(fixture());

        assertEquals(2, page.stocks().size());
        ScreenedStock lici = page.stocks().getFirst();
        assertEquals("LICI", lici.symbol());
        assertEquals("Life Insurance", lici.name());
        assertEquals(23.98, lici.ratio(ScreenerRatios.QTR_PROFIT_GROWTH));
        assertEquals(31.50, lici.ratio(ScreenerRatios.PROFIT_GROWTH_3Y));
        assertEquals(-1.11, lici.ratio(ScreenerRatios.FII_CHANGE));
        assertEquals(483229.82, lici.ratio("Market Capitalization"), "Indian digit grouping is not a decimal");
    }

    @Test
    @DisplayName("decodes symbols, and leaves a blank cell unknown rather than zero")
    void decodesSymbolsAndBlanks() throws IOException {
        ScreenedStock mm = ScreenerResultsParser.parse(fixture()).stocks().get(1);

        assertEquals("M&M", mm.symbol());
        assertEquals("Mahindra & Mahindra", mm.name());
        assertNull(mm.ratio(ScreenerRatios.PROFIT_GROWTH_3Y));
    }

    @Test
    @DisplayName("skips BSE-only companies, which the NSE feed cannot price, and reads the page count")
    void skipsBseOnlyAndReadsPages() throws IOException {
        ScreenerResultsParser.Page page = ScreenerResultsParser.parse(fixture());

        assertEquals(1, page.skippedNonNse());
        assertEquals(3, page.totalPages());
    }

    @Test
    @DisplayName("a page without a results table is reported as such, not as zero results")
    void noTable() {
        assertNull(ScreenerResultsParser.parse("<html><form><input name=\"password\"></form></html>"));
    }
}
