package com.stockanalyzer.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockAnalysisOutcomeTest {

    @Test
    void createsSuccessfulOutcomeFromResult() {
        GrowthAnalysisResult result = new GrowthAnalysisResult("ABC", GrowthTrend.BULLISH, 0.75, 0.95);

        StockAnalysisOutcome outcome = StockAnalysisOutcome.success(result);

        assertEquals("ABC", outcome.symbol());
        assertEquals(result, outcome.result());
        assertNull(outcome.errorMessage());
        assertTrue(outcome.isSuccess());
    }

    @Test
    void createsFailureOutcomeWithErrorMessage() {
        StockAnalysisOutcome outcome = StockAnalysisOutcome.failure("ABC", "request failed");

        assertEquals("ABC", outcome.symbol());
        assertNull(outcome.result());
        assertEquals("request failed", outcome.errorMessage());
        assertFalse(outcome.isSuccess());
    }
}
