package com.stockanalyzer.canslim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The company profile, read from sections cut out of real screener.in pages. The expected figures were read
 * off the pages themselves, not out of the parser.
 */
class CompanyProfileParserTest {

    private static CompanyProfile profile(String fixture) throws IOException {
        try (InputStream in = CompanyProfileParserTest.class.getResourceAsStream("/screener/" + fixture)) {
            return ScreenerCompanyParser.profile(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("the headline ratios are read, High / Low as two figures")
    void topRatios() throws IOException {
        CompanyProfile welspun = profile("company-welspunliv.html");

        assertEquals(23_968, welspun.marketCapCr(), 0.01);
        assertEquals(254, welspun.price(), 0.01);
        assertEquals(267, welspun.high52w(), 0.01);
        assertEquals(107, welspun.low52w(), 0.01);
        assertEquals(54.6, welspun.pe(), 0.01);
        assertEquals(53.6, welspun.bookValue(), 0.01);
        assertEquals(0.04, welspun.dividendYield(), 0.001);
        assertEquals(6.26, welspun.roce(), 0.001);
        assertEquals(4.48, welspun.roe(), 0.001);
        assertEquals(1.0, welspun.faceValue(), 0.001);
    }

    @Test
    @DisplayName("EPS is the trailing-twelve-month column; debt to equity is borrowings over equity plus reserves")
    void derivedFigures() throws IOException {
        CompanyProfile welspun = profile("company-welspunliv.html");

        assertEquals(4.57, welspun.epsTtm(), 0.001, "the TTM column, not the last financial year's 2.13");
        // The latest balance sheet is the half-year to September 2026: 2,405 / (94 + 4,972).
        assertEquals(2_405.0 / (94 + 4_972), welspun.debtToEquity(), 1e-9);
    }

    @Test
    @DisplayName("growth is read from the compounded-growth boxes, over the trailing year and three years")
    void growth() throws IOException {
        CompanyProfile welspun = profile("company-welspunliv.html");

        assertEquals(5, welspun.salesGrowthTtm(), 0.001);
        assertEquals(5, welspun.salesGrowth3y(), 0.001);
        assertEquals(24, welspun.profitGrowthTtm(), 0.001);
        assertEquals(4, welspun.profitGrowth3y(), 0.001);
    }

    @Test
    @DisplayName("the latest eight quarters of revenue, profit and earnings per share, oldest first")
    void quarters() throws IOException {
        CompanyProfile welspun = profile("company-welspunliv.html");

        assertEquals(YearMonth.of(2026, 9), welspun.resultsQuarter());
        assertEquals(8, welspun.quarters().size());
        CompanyProfile.Quarter oldest = welspun.quarters().getFirst();
        assertEquals(YearMonth.of(2024, 12), oldest.period());
        assertEquals(2_490, oldest.sales(), 0.01);
        assertEquals(123, oldest.netProfit(), 0.01);
        assertEquals(1.26, oldest.eps(), 0.001);
        CompanyProfile.Quarter latest = welspun.quarters().getLast();
        assertEquals(YearMonth.of(2026, 9), latest.period());
        assertEquals(2_838, latest.sales(), 0.01);
        assertEquals(170, latest.netProfit(), 0.01);
        assertEquals(1.79, latest.eps(), 0.001);
    }

    @Test
    @DisplayName("the shareholding pattern keeps promoters, FIIs, DIIs, the public and the number of holders")
    void shareholding() throws IOException {
        CompanyProfile welspun = profile("company-welspunliv.html");

        assertEquals(YearMonth.of(2026, 9), welspun.holdingQuarter());
        assertEquals(8, welspun.holdings().size());
        CompanyProfile.Holding latest = welspun.holdings().getLast();
        assertEquals(YearMonth.of(2026, 9), latest.period());
        assertEquals(63.77, latest.promoters(), 0.001);
        assertEquals(9.07, latest.fiis(), 0.001);
        assertEquals(12.09, latest.diis(), 0.001);
        assertEquals(0.0, latest.government(), 0.001);
        assertEquals(14.71, latest.publicShare(), 0.001);
        assertEquals(0.35, latest.others(), 0.001);
        assertEquals(203_526, latest.shareholders(), 0.5, "Indian digit grouping, 2,03,526");
    }

    @Test
    @DisplayName("the about text loses its footnote links, and the sector comes broadest first")
    void aboutAndSector() throws IOException {
        CompanyProfile welspun = profile("company-welspunliv.html");

        assertTrue(welspun.about().startsWith("Welspun Living Limited, part of the US$ 2.7 billion Welspun Group"),
                welspun.about());
        assertFalse(welspun.about().contains("[1]"), "the footnote markers are dropped");
        assertTrue(welspun.about().contains("Home & Technical textile"), "entities are decoded");
        assertTrue(welspun.about().length() <= 1_300, "long text is cut: " + welspun.about().length());
        assertEquals("Consumer Discretionary › Textiles › Textiles & Apparels › Other Textile Products",
                welspun.classification());
    }

    @Test
    @DisplayName("a bank has no Sales line and still gets its revenue, profit and shareholding")
    void aBankStillYieldsAProfile() throws IOException {
        CompanyProfile bank = profile("company-hdfcbank.html");

        assertFalse(bank.isEmpty());
        assertFalse(bank.quarters().isEmpty());
        assertNotNull(bank.quarters().getLast().sales(), "a bank's top line is labelled Revenue");
        assertNotNull(bank.quarters().getLast().netProfit());
        assertFalse(bank.holdings().isEmpty());
        assertNotNull(bank.marketCapCr());
    }

    @Test
    @DisplayName("a page with only a shareholding table is not a profile: every ratio blank, no quarters")
    void aBlankConsolidatedPageIsNotAProfile() throws IOException {
        // SBI Life's consolidated page, cut from the real one: the ratios are empty, the quarters table has no
        // rows, and the shareholding table is full. Its standalone page carries the figures.
        CompanyProfile blank = profile("company-sbilife-consolidated.html");

        assertFalse(blank.holdings().isEmpty(), "the page does have a shareholding table");
        assertNull(blank.marketCapCr());
        assertTrue(blank.quarters().isEmpty());
        assertTrue(blank.isEmpty(), "so the client must go on to the standalone page");
    }

    @Test
    @DisplayName("a holder the quarterly shareholding lacks is blank, not taken from the yearly table under it")
    void aHolderTheQuartersLackIsNotReadFromTheYears() throws IOException {
        // HDFC Bank's consolidated page, cut from the real one. Since its 2023 merger it has no promoters, so the
        // quarterly table has no Promoters row; the yearly table below it still has one (26.00% in Mar 2017 ...
        // 25.97% in Mar 2021 ... 0.00% from Mar 2024), and neither table has an Others row. The quarterly table,
        // as printed for Sep 2026: FIIs 39.39%, DIIs 43.44%, Government 0.19%, Public 16.96%, 48,07,998 holders;
        // for Jun 2026: FIIs 41.82%.
        String page;
        try (InputStream in = CompanyProfileParserTest.class.getResourceAsStream("/screener/company-hdfcbank-consolidated.html")) {
            page = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        CompanyProfile bank = ScreenerCompanyParser.profile(page);

        assertEquals(8, bank.holdings().size());
        assertEquals(YearMonth.of(2024, 12), bank.holdings().getFirst().period());
        assertEquals(YearMonth.of(2026, 9), bank.holdings().getLast().period());
        for (CompanyProfile.Holding quarter : bank.holdings()) {
            assertNull(quarter.promoters(), quarter.period() + ": no promoters, not a year's figure");
            assertNull(quarter.others(), quarter.period() + ": no Others row in either table");
            double whole = quarter.fiis() + quarter.diis() + quarter.government() + quarter.publicShare();
            assertEquals(100.0, whole, 0.05, quarter.period() + ": the parts make the whole company");
        }
        CompanyProfile.Holding latest = bank.holdings().getLast();
        assertEquals(39.39, latest.fiis(), 1e-9);
        assertEquals(43.44, latest.diis(), 1e-9);
        assertEquals(0.19, latest.government(), 1e-9);
        assertEquals(16.96, latest.publicShare(), 1e-9);
        assertEquals(4_807_998.0, latest.shareholders(), 1e-9);
        assertEquals(YearMonth.of(2026, 9), bank.holdingQuarter());

        CompanyFundamentals forCanSlim = ScreenerCompanyParser.fundamentals(page);
        assertEquals(39.39 - 41.82, forCanSlim.ratios().get(ScreenerRatios.FII_CHANGE), 1e-9,
                "the CAN SLIM scan reads the same table");
        assertEquals(YearMonth.of(2026, 9), forCanSlim.holdingQuarter());
    }

    @Test
    @DisplayName("a page with no figures, such as an ETF, is an empty profile")
    void anEtfHasNoProfile() throws IOException {
        CompanyProfile etf = profile("company-etf.html");

        assertTrue(etf.isEmpty());
        assertTrue(etf.quarters().isEmpty());
        assertNull(etf.epsTtm());
        assertNull(etf.debtToEquity());
    }
}
