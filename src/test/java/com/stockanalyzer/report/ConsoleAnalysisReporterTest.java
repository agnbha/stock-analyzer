package com.stockanalyzer.report;

import com.stockanalyzer.model.GrowthAnalysisResult;
import com.stockanalyzer.model.GrowthTrend;
import com.stockanalyzer.model.StockAnalysisOutcome;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleAnalysisReporterTest {

    @Test
    void sortsSuccessfulResultsAndPrintsFailures() {
        StockAnalysisOutcome lower = StockAnalysisOutcome.success(
                new GrowthAnalysisResult("LOW", GrowthTrend.NEUTRAL, 0.2, 0.7));
        StockAnalysisOutcome higher = StockAnalysisOutcome.success(
                new GrowthAnalysisResult("HIGH", GrowthTrend.BULLISH, 0.8, 0.9));
        StockAnalysisOutcome failure = StockAnalysisOutcome.failure("BAD", "upstream unavailable");

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            new ConsoleAnalysisReporter().report(List.of(lower, failure, higher));
        } finally {
            System.setOut(originalOut);
        }

        String output = captured.toString(StandardCharsets.UTF_8);
        assertTrue(output.indexOf("HIGH") < output.indexOf("LOW"));
        assertTrue(output.contains("Failed (1):"));
        assertTrue(output.contains("BAD"));
        assertTrue(output.contains("Analyzed 2/3 symbols successfully."));
    }

    @Test
    void printsSuccessSummaryWhenThereAreNoFailures() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            new ConsoleAnalysisReporter().report(List.of(StockAnalysisOutcome.success(
                    new GrowthAnalysisResult("ONLY", GrowthTrend.UNKNOWN, 0.0, 0.5))));
        } finally {
            System.setOut(originalOut);
        }

        String output = captured.toString(StandardCharsets.UTF_8);
        assertTrue(!output.contains("Failed ("));
        assertTrue(output.contains("Analyzed 1/1 symbols successfully."));
    }
}
