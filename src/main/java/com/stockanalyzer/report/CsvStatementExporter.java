package com.stockanalyzer.report;

import com.stockanalyzer.model.RealizedLot;
import com.stockanalyzer.util.MarketClock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** One row per realized lot - the spreadsheet-ready form of the statement. */
public final class CsvStatementExporter {

    private final MarketClock clock;

    public CsvStatementExporter(MarketClock clock) {
        this.clock = clock;
    }

    public void export(List<RealizedLot> lots, Path target) {
        StringBuilder csv = new StringBuilder("symbol,product,quantity,opened,closed,holding_minutes,"
                + "buy_price,sell_price,gross_pnl,charges,net_pnl,return_pct\n");
        for (RealizedLot lot : lots) {
            csv.append(String.format(Locale.ROOT, "%s,%s,%d,%s,%s,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.4f%n",
                    lot.symbol(), lot.product(), lot.quantity(),
                    Instant.ofEpochSecond(lot.openedTs()).atZone(clock.zone()).toLocalDateTime(),
                    Instant.ofEpochSecond(lot.closedTs()).atZone(clock.zone()).toLocalDateTime(),
                    lot.holdingMinutes(), lot.buyPrice(), lot.sellPrice(),
                    lot.grossPnl(), lot.chargesAllocated(), lot.netPnl(), lot.returnPct()));
        }
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, csv.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + target, e);
        }
        System.out.printf("Wrote %d realized lots to %s%n", lots.size(), target);
    }
}
