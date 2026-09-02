package com.stockanalyzer;

import com.stockanalyzer.config.AppConfig;
import com.stockanalyzer.model.AccountSnapshot;
import com.stockanalyzer.model.PeriodType;
import com.stockanalyzer.model.PnlSummary;
import com.stockanalyzer.model.Product;
import com.stockanalyzer.model.RealizedLot;
import com.stockanalyzer.model.Side;
import com.stockanalyzer.model.Trade;
import com.stockanalyzer.model.TradeAttribution;
import com.stockanalyzer.report.CaptureReporter;
import com.stockanalyzer.report.ConsolePnlReporter;
import com.stockanalyzer.report.CsvStatementExporter;
import com.stockanalyzer.trade.CsvTradeImporter;
import com.stockanalyzer.trade.ExecutionDataClient;
import com.stockanalyzer.trade.GrowwExecutionClient;
import com.stockanalyzer.trade.PeriodBounds;
import com.stockanalyzer.trade.TradeIds;
import com.stockanalyzer.store.TradeReasonRepository;
import com.stockanalyzer.util.Args;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Part 5 entry point: the trade journal and the gain/loss statement.
 *
 * <pre>
 *   trades import --broker --from D --to D    pull the broker trade book
 *   trades import --csv FILE                  import a CSV or contract note
 *   trades add --symbol S --side BUY --qty N --price P --at TS [--product MIS] [--reason "..."]
 *   trades reasons [--month YYYY-MM]          attribute why each trade was taken
 *   trades balance [--date D]                 read the balance from the broker
 *   trades balance --cash X [--date D]        record it by hand instead
 *   pnl --period day|week|month|fy [--date D | --of D | --month YYYY-MM | --year YYYY-YY]
 *   pnl --rebuild                             re-match every stored trade
 *   statement --from D --to D --out FILE      one row per realized lot
 *   capture --month YYYY-MM                   what you took vs what was available
 * </pre>
 */
public final class TradeJournalMain {

    public static void main(String[] args) {
        Args parsed = Args.parse(args);
        String command = parsed.command("pnl");

        try (AppContext context = new AppContext(AppConfig.load())) {
            switch (command) {
                case "trades" -> trades(context, parsed);
                case "pnl" -> pnl(context, parsed);
                case "statement" -> statement(context, parsed);
                case "capture" -> capture(context, parsed);
                default -> {
                    System.err.println("Unknown command: " + command);
                    System.err.println("Try: trades | pnl | statement | capture");
                    System.exit(2);
                }
            }
        }
    }

    private static void trades(AppContext context, Args args) {
        String subcommand = args.subcommand("import");
        switch (subcommand) {
            case "add" -> {
                addManualTrade(context, args);
                return;
            }
            case "reasons" -> {
                reasons(context, args);
                return;
            }
            case "balance" -> {
                balance(context, args);
                return;
            }
            default -> { }
        }

        LocalDate to = args.date("to", LocalDate.now(context.clock().zone()));
        LocalDate from = args.date("from", to.minusMonths(1));
        AppConfig config = context.config();

        ExecutionDataClient client;
        if (args.has("csv")) {
            client = new CsvTradeImporter(Path.of(args.require("csv")), config.tradesCsvDateFormat(),
                    context.defaultProduct(), context.clock());
        } else {
            client = new GrowwExecutionClient(context.httpClient(), context.authenticator(),
                    config.growwBaseUrl(), context.clock(), context.defaultProduct(),
                    config.segment(), context.rateLimiter());
        }

        int inserted = context.tradeJournalService().importTrades(client, from, to);
        var result = context.tradeJournalService().rebuild();
        int attributed = attributeReasons(context, from, to);
        System.out.printf("Imported %d new trades; matched %d closed lots, %d positions open, "
                        + "%d reasons attributed%n",
                inserted, result.lots().size(), result.openPositions().size(), attributed);
    }

    private static void addManualTrade(AppContext context, Args args) {
        String symbol = args.require("symbol").toUpperCase();
        Side side = Side.valueOf(args.require("side").toUpperCase());
        int quantity = Integer.parseInt(args.require("qty"));
        double price = Double.parseDouble(args.require("price"));
        Product product = Product.valueOf(args.value("product").orElse(context.defaultProduct().name())
                .toUpperCase());
        long executedTs = LocalDateTime.parse(args.require("at")).atZone(context.clock().zone()).toEpochSecond();

        Trade trade = new Trade(0, symbol,
                TradeIds.synthetic("manual", symbol, side, product, quantity, price, executedTs),
                null, context.clock().sessionDateOf(executedTs), executedTs, side, quantity, price, product,
                0, null, Trade.ChargesSource.MODELLED, Trade.TradeSource.MANUAL,
                args.value("reason").or(() -> args.value("notes")).orElse(null));

        int inserted = context.tradeJournalService()
                .importTrades((from, to) -> List.of(trade), trade.sessionDate(), trade.sessionDate());
        context.tradeJournalService().rebuild();
        attributeReasons(context, trade.sessionDate(), trade.sessionDate());
        System.out.println(inserted == 1 ? "Added." : "Already recorded; nothing changed.");
    }

    private static void pnl(AppContext context, Args args) {
        if (args.flag("rebuild")) {
            var result = context.tradeJournalService().rebuild();
            System.out.printf("Rebuilt: %d closed lots, %d positions open%n",
                    result.lots().size(), result.openPositions().size());
            return;
        }

        PeriodType periodType = PeriodType.valueOf(args.value("period").orElse("day").toUpperCase());
        LocalDate anchor = anchorFor(context, args, periodType);
        String symbol = args.value("symbol").orElse(null);

        PnlSummary summary = context.tradeJournalService().pnl(periodType, anchor, symbol);
        List<PnlSummary> breakdown = context.tradeJournalService().breakdown(periodType, anchor, symbol);
        new ConsolePnlReporter().report(
                new PeriodBounds(context.config().financialYearStartMonth()).label(periodType, anchor),
                summary, breakdown, context.tradeJournalService().openPositions());
    }

    private static LocalDate anchorFor(AppContext context, Args args, PeriodType periodType) {
        LocalDate today = LocalDate.now(context.clock().zone());
        return switch (periodType) {
            case DAY -> args.date("date", today);
            case WEEK -> args.date("of", today);
            case MONTH -> args.value("month")
                    .map(month -> LocalDate.parse(month + "-01"))
                    .orElse(today);
            case FY -> args.value("year")
                    .map(year -> LocalDate.of(Integer.parseInt(year.split("-")[0]),
                            context.config().financialYearStartMonth(), 1))
                    .orElse(today);
        };
    }

    private static void statement(AppContext context, Args args) {
        LocalDate from = LocalDate.parse(args.require("from"));
        LocalDate to = args.date("to", LocalDate.now(context.clock().zone()));
        Path out = Path.of(args.value("out").orElse("statement.csv"));
        List<RealizedLot> lots = context.realizedLotRepository().findClosedBetween(from, to);
        new CsvStatementExporter(context.clock()).export(lots, out);
    }

    private static void capture(AppContext context, Args args) {
        PeriodBounds bounds = new PeriodBounds(context.config().financialYearStartMonth());
        LocalDate anchor = args.value("month")
                .map(month -> LocalDate.parse(month + "-01"))
                .orElse(LocalDate.now(context.clock().zone()));
        LocalDate from = bounds.startOf(PeriodType.MONTH, anchor);
        LocalDate to = bounds.endOf(PeriodType.MONTH, anchor);

        List<Trade> trades = context.tradeRepository().findRange(from, to);
        List<TradeAttribution> attributions = context.captureAnalyzer().analyse(trades, from, to);
        context.attributionRepository().upsertAll(attributions);
        new CaptureReporter().report(bounds.label(PeriodType.MONTH, anchor), attributions);
    }


    /** Recomputes why each trade in the range was taken. Safe to re-run. */
    private static int attributeReasons(AppContext context, LocalDate from, LocalDate to) {
        List<Trade> trades = context.tradeRepository().findRange(from, to);
        if (trades.isEmpty()) {
            return 0;
        }
        context.tradeReasonRepository().deleteForTrades(trades.stream().map(Trade::id).toList());
        List<TradeReasonRepository.TradeReason> reasons = context.reasonAttributor().attribute(trades);
        context.tradeReasonRepository().upsertAll(reasons);
        return reasons.size();
    }

    private static void reasons(AppContext context, Args args) {
        PeriodBounds bounds = new PeriodBounds(context.config().financialYearStartMonth());
        LocalDate anchor = args.value("month")
                .map(month -> LocalDate.parse(month + "-01"))
                .orElse(LocalDate.now(context.clock().zone()));
        LocalDate from = args.date("from", bounds.startOf(PeriodType.MONTH, anchor));
        LocalDate to = args.date("to", bounds.endOf(PeriodType.MONTH, anchor));

        int attributed = attributeReasons(context, from, to);
        Map<String, Long> frequency = context.tradeReasonRepository().findBetween(from, to).stream()
                .collect(Collectors.groupingBy(TradeReasonRepository.TradeReason::reasonCode,
                        TreeMap::new, Collectors.counting()));

        System.out.printf("%nWhy trades were taken, %s to %s (%d reasons across %d trades)%n%n",
                from, to, attributed, context.tradeRepository().findRange(from, to).size());
        if (frequency.isEmpty()) {
            System.out.println("No trades in this period.");
            return;
        }
        long max = frequency.values().stream().mapToLong(Long::longValue).max().orElse(1);
        frequency.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(entry -> System.out.printf("  %-34s %-30s %3d%n", entry.getKey(),
                        "#".repeat((int) Math.max(1, entry.getValue() * 30 / max)), entry.getValue()));
        System.out.println();
    }

    /**
     * Records what the account is worth. Reads it from the broker by default -
     * a balance typed in once and carried forward stops being true at the first
     * deposit, dividend or fill this process never saw. {@code --cash} is the
     * fallback for when the API cannot be reached, and is marked as such.
     */
    private static void balance(AppContext context, Args args) {
        LocalDate date = args.date("date", LocalDate.now(context.clock().zone()));

        if (args.value("cash").isPresent()) {
            double cash = Double.parseDouble(args.require("cash"));
            Double invested = args.value("invested").map(Double::parseDouble).orElse(null);
            context.accountBalanceRepository().record(date, cash, invested, "manual");
            System.out.printf("Recorded balance for %s by hand: cash %s%s%n", date, rupees(cash),
                    invested == null ? "" : ", invested " + rupees(invested));
            return;
        }

        AccountSnapshot snapshot = context.accountDataClient().fetch();
        context.accountBalanceRepository().record(date, snapshot);
        printBalance(date, snapshot);
    }

    private static void printBalance(LocalDate date, AccountSnapshot snapshot) {
        System.out.printf("%nAccount as of %s, from the broker%n%n", date);
        System.out.printf("  Credit balance (cash)   %14s%n", rupees(snapshot.cash()));
        System.out.printf("    blocked as margin     %14s%n", rupees(snapshot.marginUsed()));
        System.out.printf("    free to deploy        %14s%n", rupees(snapshot.available()));
        if (snapshot.collateral() > 0) {
            System.out.printf("  Collateral available    %14s%n", rupees(snapshot.collateral()));
        }
        System.out.printf("  Holdings (%d)            %14s%n",
                snapshot.holdings().size(), rupees(snapshot.holdingsValue()));
        System.out.printf("  %-23s %14s%n", "Account value", rupees(snapshot.totalValue()));

        if (snapshot.unpricedHoldings() > 0) {
            System.out.printf("%n  Note: %d holding(s) had no quote and are counted at cost, so the%n"
                            + "        account value is approximate.%n",
                    snapshot.unpricedHoldings());
        }
        if (!snapshot.holdings().isEmpty()) {
            System.out.printf("%n  %-12s %8s %12s %12s %14s %10s%n",
                    "SYMBOL", "QTY", "AVG", "LAST", "VALUE", "UNREALISED");
            snapshot.holdings().stream()
                    .sorted(Comparator.comparingDouble(AccountSnapshot.Holding::value).reversed())
                    .forEach(h -> System.out.printf("  %-12s %8.0f %12s %12s %14s %10s%n",
                            h.symbol(), h.quantity(), rupees(h.averagePrice()),
                            h.lastPrice() == null ? "-" : rupees(h.lastPrice()),
                            rupees(h.value()),
                            h.unrealised() == null ? "-" : rupees(h.unrealised())));
        }
        System.out.println();
    }

    private static String rupees(double amount) {
        return String.format(Locale.ROOT, "%,.2f", amount);
    }

    private TradeJournalMain() {
    }
}
