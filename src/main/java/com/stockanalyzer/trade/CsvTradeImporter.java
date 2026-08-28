package com.stockanalyzer.trade;

import com.stockanalyzer.model.Product;
import com.stockanalyzer.model.Side;
import com.stockanalyzer.model.Trade;
import com.stockanalyzer.util.MarketClock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads fills from a CSV export or contract note.
 *
 * <p>Column names are matched case-insensitively from the header row, so a
 * broker's own export usually works unedited. Required columns: symbol, side,
 * quantity, price, and either {@code timestamp} or {@code date}. Optional:
 * product, charges, order_id, trade_id.
 */
public final class CsvTradeImporter implements ExecutionDataClient {

    private final Path file;
    private final DateTimeFormatter timestampFormat;
    private final Product defaultProduct;
    private final MarketClock clock;

    public CsvTradeImporter(Path file, String timestampPattern, Product defaultProduct, MarketClock clock) {
        this.file = file;
        this.timestampFormat = DateTimeFormatter.ofPattern(timestampPattern);
        this.defaultProduct = defaultProduct;
        this.clock = clock;
    }

    @Override
    public List<Trade> fetchTrades(LocalDate from, LocalDate to) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read trade CSV " + file, e);
        }
        if (lines.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> columns = new HashMap<>();
        String[] header = lines.getFirst().split(",");
        for (int i = 0; i < header.length; i++) {
            columns.put(header[i].trim().toLowerCase().replace(' ', '_'), i);
        }
        require(columns, "symbol");
        require(columns, "side");
        require(columns, "quantity");
        require(columns, "price");

        List<Trade> trades = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] cells = line.split(",", -1);
            String symbol = cell(cells, columns, "symbol").toUpperCase();
            Side side = Side.valueOf(cell(cells, columns, "side").toUpperCase());
            int quantity = Integer.parseInt(cell(cells, columns, "quantity").trim());
            double price = Double.parseDouble(cell(cells, columns, "price").trim());
            Product product = columns.containsKey("product") && !cell(cells, columns, "product").isBlank()
                    ? Product.valueOf(cell(cells, columns, "product").trim().toUpperCase())
                    : defaultProduct;
            long executedTs = parseTimestamp(cells, columns);
            LocalDate sessionDate = clock.sessionDateOf(executedTs);
            if (sessionDate.isBefore(from) || sessionDate.isAfter(to)) {
                continue;
            }
            double charges = columns.containsKey("charges") && !cell(cells, columns, "charges").isBlank()
                    ? Double.parseDouble(cell(cells, columns, "charges").trim())
                    : 0.0;
            String brokerTradeId = columns.containsKey("trade_id") && !cell(cells, columns, "trade_id").isBlank()
                    ? cell(cells, columns, "trade_id").trim()
                    : TradeIds.synthetic("csv", symbol, side, product, quantity, price, executedTs);

            trades.add(new Trade(0, symbol, brokerTradeId,
                    columns.containsKey("order_id") ? cell(cells, columns, "order_id").trim() : null,
                    sessionDate, executedTs, side, quantity, price, product, charges, null,
                    charges > 0 ? Trade.ChargesSource.BROKER : Trade.ChargesSource.MODELLED,
                    Trade.TradeSource.CSV, null));
        }
        return trades;
    }

    private long parseTimestamp(String[] cells, Map<String, Integer> columns) {
        if (columns.containsKey("timestamp")) {
            return LocalDateTime.parse(cell(cells, columns, "timestamp").trim(), timestampFormat)
                    .atZone(clock.zone()).toEpochSecond();
        }
        if (columns.containsKey("date")) {
            LocalDate date = LocalDate.parse(cell(cells, columns, "date").trim());
            return clock.epochOf(date, clock.sessionOpen());
        }
        throw new IllegalStateException("Trade CSV needs a 'timestamp' or 'date' column");
    }

    private static void require(Map<String, Integer> columns, String name) {
        if (!columns.containsKey(name)) {
            throw new IllegalStateException("Trade CSV is missing the '" + name + "' column");
        }
    }

    private static String cell(String[] cells, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        return index == null || index >= cells.length ? "" : cells[index];
    }
}
