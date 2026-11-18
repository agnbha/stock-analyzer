package com.stockanalyzer.canslim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The filings and earnings calls a screener.in company page lists under Documents, as signed out. */
class ScreenerDocumentsParserTest {

    private static String page() throws IOException {
        try (InputStream in = ScreenerDocumentsParserTest.class.getResourceAsStream("/screener/company-documents.html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("the five filings, newest first: subject, time, screener.in's line on it when there is one, and its link")
    void announcements() throws IOException {
        List<CompanyProfile.Announcement> filings = ScreenerCompanyParser.announcements(page());

        assertEquals(5, filings.size(), "the annual reports listed after them are not filings");
        CompanyProfile.Announcement first = filings.getFirst();
        assertEquals("Announcement under Regulation 30 (LODR)-Change in Management", first.title());
        assertEquals(Instant.parse("2026-11-13T09:46:15Z"), first.at(), "15:16:15 IST");
        assertEquals("Chief Financial Officer resigns; the board names the Company's treasurer as interim CFO.",
                first.summary(), "without the dash that joins it to the date, and with the apostrophe decoded");
        assertEquals("https://www.bseindia.com/stockinfo/AnnPdfOpen.aspx?Pname=aaaa1111.pdf", first.url());

        CompanyProfile.Announcement routine = filings.get(1);
        assertEquals("Announcement under Regulation 30 (LODR)-Analyst / Investor Meet - Intimation", routine.title(),
                "a dash inside the subject stays");
        assertNull(routine.summary(), "a routine filing has a date and no line");
        assertEquals(Instant.parse("2026-11-04T06:51:04Z"), routine.at());

        CompanyProfile.Announcement scheme = filings.get(2);
        assertEquals("Board approves merging its wholly owned subsidiary into the company & a demerger of the "
                + "packaging arm.", scheme.summary());
        assertEquals("https://www.bseindia.com/stockinfo/AnnPdfOpen.aspx?Pname=cccc3333.pdf&type=new", scheme.url());

        for (int i = 1; i < filings.size(); i++) {
            assertTrue(filings.get(i).at().isBefore(filings.get(i - 1).at()), "newest first");
        }
    }

    @Test
    @DisplayName("the latest eight calls, newest first, each with the links the page gives; signed out, no transcript")
    void concalls() throws IOException {
        List<CompanyProfile.Concall> calls = ScreenerCompanyParser.concalls(page());

        assertEquals(8, calls.size(), "nine listed, the oldest left out");
        CompanyProfile.Concall latest = calls.getFirst();
        assertEquals(YearMonth.of(2026, 10), latest.period());
        assertEquals("https://www.bseindia.com/stockinfo/AnnPdfOpen.aspx?Pname=ppt-oct-2026.pdf",
                latest.presentationUrl());
        assertEquals("https://example.com/recordings/oct-2026", latest.recordingUrl());
        assertNull(latest.transcriptUrl(), "a label without a link, signed out");
        assertNull(latest.summaryUrl(), "the same");

        CompanyProfile.Concall may = calls.get(2);
        assertEquals(YearMonth.of(2026, 5), may.period());
        assertNull(may.presentationUrl(), "a call listed with no presentation");
        assertEquals(YearMonth.of(2025, 1), calls.getLast().period());
    }

    @Test
    @DisplayName("a page without a Documents section, an ETF's, has no filings and no calls")
    void none() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/screener/company-etf.html")) {
            CompanyProfile etf = ScreenerCompanyParser.profile(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(List.of(), etf.announcements());
            assertEquals(List.of(), etf.concalls());
        }
    }
}
