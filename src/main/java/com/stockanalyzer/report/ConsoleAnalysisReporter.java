package com.stockanalyzer.report;

import com.stockanalyzer.model.StockAnalysisOutcome;

import java.util.Comparator;
import java.util.List;

/** Prints a simple ranked table to stdout, successes sorted by growth score descending, then failures. */
public final class ConsoleAnalysisReporter implements AnalysisReporter {

    @Override
    public void report(List<StockAnalysisOutcome> outcomes) {
        List<StockAnalysisOutcome> successes = outcomes.stream()
                .filter(StockAnalysisOutcome::isSuccess)
                .sorted(Comparator.comparingDouble((StockAnalysisOutcome o) -> o.result().growthScore()).reversed())
                .toList();
        List<StockAnalysisOutcome> failures = outcomes.stream()
                .filter(o -> !o.isSuccess())
                .toList();

        System.out.printf("%-10s %-10s %12s %12s%n", "SYMBOL", "TREND", "SCORE", "CONFIDENCE");
        System.out.println("-".repeat(48));
        for (StockAnalysisOutcome outcome : successes) {
            var r = outcome.result();
            System.out.printf("%-10s %-10s %12.4f %12.2f%n", r.symbol(), r.trend(), r.growthScore(), r.confidence());
        }

        if (!failures.isEmpty()) {
            System.out.println();
            System.out.println("Failed (" + failures.size() + "):");
            for (StockAnalysisOutcome outcome : failures) {
                System.out.printf("  %-10s %s%n", outcome.symbol(), outcome.errorMessage());
            }
        }

        System.out.println();
        System.out.printf("Analyzed %d/%d symbols successfully.%n", successes.size(), outcomes.size());
    }
}
