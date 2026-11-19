package com.stockanalyzer.canslim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** screener.in's concall summary, as lines with their depth. The company and its call are invented. */
class ConcallSummaryTest {

    @Test
    @DisplayName("headings, points and points under points, in order, as plain text; and the allowance under it")
    void lines() throws IOException {
        String page;
        try (InputStream in = getClass().getResourceAsStream("/screener/concall-summary.html")) {
            page = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        ConcallSummary summary = ConcallSummary.parse(page);

        assertEquals(List.of(
                new ConcallSummary.Line(0, "Q2 FY27: Key takeaways"),
                new ConcallSummary.Line(1, "Revenue grew 12% YoY: management called demand “broad-based across "
                        + "regions”."),
                new ConcallSummary.Line(1, "Two new customers in exports:"),
                new ConcallSummary.Line(2, "A five-year supply contract with a European utility."),
                new ConcallSummary.Line(2, "One large customer lost to a cheaper rival & replaced within the quarter."),
                new ConcallSummary.Line(0, "People and structure"),
                new ConcallSummary.Line(1, "The CFO retires in March; the board named the treasurer as successor."),
                new ConcallSummary.Line(1, "The packaging arm will be demerged into a listed subsidiary.")),
                summary.lines(), "no space left where a <strong> was, and nothing from after the article");
        assertEquals(3, summary.viewed());
        assertEquals(10, summary.allowance());
    }

    @Test
    @DisplayName("a page without a summary, the login page say, has no lines and no allowance")
    void notASummary() {
        ConcallSummary summary = ConcallSummary.parse("<html><form><input name=\"password\"></form></html>");
        assertTrue(summary.lines().isEmpty());
        assertNull(summary.viewed());
    }
}
