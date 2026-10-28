package com.stockanalyzer.canslim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixtures are the relevant sections cut from real screener.in company pages. */
class ScreenerCompanyParserTest {

    private static Map<String, Double> parse(String fixture) throws IOException {
        try (InputStream in = ScreenerCompanyParserTest.class.getResourceAsStream("/screener/" + fixture)) {
            return ScreenerCompanyParser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static CompanyFundamentals fundamentals(String fixture) throws IOException {
        try (InputStream in = ScreenerCompanyParserTest.class.getResourceAsStream("/screener/" + fixture)) {
            return ScreenerCompanyParser.fundamentals(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("the newest quarter each table shows is read, so a copy can be judged by the calendar")
    void quarterLabels() throws IOException {
        // ITC's page was cut after the shareholding for September arrived but before its results did.
        CompanyFundamentals itc = fundamentals("company-itc.html");
        assertEquals(java.time.YearMonth.of(2026, 6), itc.resultsQuarter());
        assertEquals(java.time.YearMonth.of(2026, 9), itc.holdingQuarter());

        CompanyFundamentals hdfc = fundamentals("company-hdfcbank.html");
        assertEquals(java.time.YearMonth.of(2026, 9), hdfc.resultsQuarter());
        assertEquals(java.time.YearMonth.of(2026, 9), hdfc.holdingQuarter());
    }

    @Test
    @DisplayName("a page without the tables has no quarters, and no ratios")
    void noQuarters() throws IOException {
        CompanyFundamentals etf = fundamentals("company-etf.html");
        assertNull(etf.resultsQuarter());
        assertNull(etf.holdingQuarter());
        assertTrue(etf.isEmpty());
    }

    @Test
    @DisplayName("C: latest quarter against the same quarter a year earlier")
    void quarterlyGrowthIsYearOnYear() throws IOException {
        Map<String, Double> itc = parse("company-itc.html");

        // Net profit 4,509 in the latest quarter against 5,343 four quarters before.
        assertEquals(-15.6, itc.get(ScreenerRatios.QTR_PROFIT_GROWTH), 0.1);
        assertEquals(-11.1, itc.get(ScreenerRatios.QTR_SALES_GROWTH), 0.1);
    }

    @Test
    @DisplayName("A: three-year compounded profit growth, and return on equity")
    void annualFigures() throws IOException {
        Map<String, Double> itc = parse("company-itc.html");

        assertEquals(3.0, itc.get(ScreenerRatios.PROFIT_GROWTH_3Y), 1e-9);
        assertEquals(29.3, itc.get(ScreenerRatios.ROE), 1e-9);
        assertEquals(318749.0, itc.get("Market Capitalization"), 1e-9, "Indian digit grouping is not a decimal");
    }

    @Test
    @DisplayName("I: how far FII and DII holdings moved between the last two quarters, in points")
    void institutionalChange() throws IOException {
        Map<String, Double> itc = parse("company-itc.html");

        assertEquals(-1.01, itc.get(ScreenerRatios.FII_CHANGE), 1e-6);
        assertEquals(0.68, itc.get(ScreenerRatios.DII_CHANGE), 1e-6);
    }

    @Test
    @DisplayName("a bank's top line is labelled Revenue, and is read all the same")
    void bankRevenue() throws IOException {
        Map<String, Double> bank = parse("company-hdfcbank.html");

        assertTrue(bank.containsKey(ScreenerRatios.QTR_SALES_GROWTH));
        assertEquals(9.4, bank.get(ScreenerRatios.QTR_SALES_GROWTH), 0.1);
        assertEquals(18.0, bank.get(ScreenerRatios.PROFIT_GROWTH_3Y), 1e-9);
    }

    @Test
    @DisplayName("an ETF has no fundamentals, and yields none rather than zeros")
    void etf() throws IOException {
        assertTrue(parse("company-etf.html").isEmpty());
    }

    @Test
    @DisplayName("growth off a loss-making base is unknown, not a huge number")
    void growthFromALossIsUnknown() {
        String page = """
                <section id="quarters"><table>
                <tr><th></th><th>Jun 2025</th><th>Sep 2025</th><th>Dec 2025</th><th>Mar 2026</th><th>Jun 2026</th></tr>
                <tr><td class="text">Net Profit&nbsp;+</td><td>-50</td><td>10</td><td>20</td><td>30</td><td>40</td></tr>
                <tr><td class="text">Sales&nbsp;+</td><td>100</td><td>110</td><td>120</td><td>130</td><td>150</td></tr>
                </table></section>""";

        Map<String, Double> ratios = ScreenerCompanyParser.parse(page);

        assertFalse(ratios.containsKey(ScreenerRatios.QTR_PROFIT_GROWTH));
        assertEquals(50.0, ratios.get(ScreenerRatios.QTR_SALES_GROWTH), 1e-9);
    }

    @Test
    @DisplayName("fewer than five quarters cannot give a year-on-year figure")
    void tooFewQuarters() {
        String page = """
                <section id="quarters"><table>
                <tr><th></th><th>Dec 2025</th><th>Mar 2026</th><th>Jun 2026</th></tr>
                <tr><td class="text">Net Profit&nbsp;+</td><td>10</td><td>20</td><td>30</td></tr>
                </table></section>""";

        assertTrue(ScreenerCompanyParser.parse(page).isEmpty());
    }
}
