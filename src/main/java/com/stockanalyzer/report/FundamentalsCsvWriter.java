package com.stockanalyzer.report;

import com.stockanalyzer.model.FundamentalsOutcome;
import com.stockanalyzer.model.StockFundamentals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Writes fundamentals outcomes to a CSV file; failed symbols get a row with blank fields plus an error column. */
public final class FundamentalsCsvWriter {

    private static final String[] HEADERS = {
            "symbol", "exchange", "segment", "last_price", "open", "day_high", "day_low",
            "previous_close", "day_change", "day_change_perc", "volume", "market_cap",
            "average_price", "week_52_high", "week_52_low", "upper_circuit_limit",
            "lower_circuit_limit", "error"
    };

    public void write(List<FundamentalsOutcome> outcomes, Path outputPath) {
        StringBuilder csv = new StringBuilder();
        csv.append(String.join(",", HEADERS)).append('\n');

        for (FundamentalsOutcome outcome : outcomes) {
            csv.append(toRow(outcome)).append('\n');
        }

        try {
            Files.writeString(outputPath, csv.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write fundamentals CSV to " + outputPath, e);
        }
    }

    private static String toRow(FundamentalsOutcome outcome) {
        if (!outcome.isSuccess()) {
            return String.join(",",
                    field(outcome.symbol()), "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
                    field(outcome.errorMessage()));
        }

        StockFundamentals f = outcome.fundamentals();
        return String.join(",",
                field(f.symbol()),
                field(f.exchange()),
                field(f.segment()),
                field(f.lastPrice()),
                field(f.open()),
                field(f.dayHigh()),
                field(f.dayLow()),
                field(f.previousClose()),
                field(f.dayChange()),
                field(f.dayChangePerc()),
                field(f.volume()),
                field(f.marketCap()),
                field(f.averagePrice()),
                field(f.week52High()),
                field(f.week52Low()),
                field(f.upperCircuitLimit()),
                field(f.lowerCircuitLimit()),
                "");
    }

    private static String field(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
