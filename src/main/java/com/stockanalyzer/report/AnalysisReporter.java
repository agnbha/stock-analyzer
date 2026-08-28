package com.stockanalyzer.report;

import com.stockanalyzer.model.StockAnalysisOutcome;

import java.util.List;

/** Renders a batch of analysis outcomes. New output formats (CSV, JSON, HTML) plug in here without touching the service layer. */
public interface AnalysisReporter {

    void report(List<StockAnalysisOutcome> outcomes);
}
