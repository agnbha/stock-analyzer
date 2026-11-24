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
import com.stockanalyzer.note.NoteDashboard;
import com.stockanalyzer.report.CaptureReporter;
import com.stockanalyzer.report.ConsolePnlReporter;
import com.stockanalyzer.report.CsvStatementExporter;
import com.stockanalyzer.trade.BrokerFillSync;
import com.stockanalyzer.trade.CsvTradeImporter;
import com.stockanalyzer.trade.ExecutionDataClient;
import com.stockanalyzer.trade.PeriodBounds;
import com.stockanalyzer.trade.TradeIds;
import com.stockanalyzer.trade.TradeNoteService;
import com.stockanalyzer.store.TradeReasonRepository;
import com.stockanalyzer.util.Args;
import com.stockanalyzer.store.AccountProfileRepository;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
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
 *   trades note --symbol S --side BUY|SELL [--sentiment BULLISH] [--text "..."]
 *               [--date D] [--at HH:MM] [--order ID] [--clear]
 *                                             record what you thought when you took a call
 *   trades notes [--date D | --from D --to D] [--symbol S]
 *                                             list the calls you have noted
 *   trades balance [--date D]                 read the balance from the broker
 *   trades balance --cash X [--date D]        record it by hand instead
 *   chartnotes sync                           copy the notes written on the Grafana charts into this database
 *   chartnotes list [--symbol S]              the notes written on the charts, with their symbol and sentiment
 *   chartnotes assign --id N[,N...] --symbol S
 *                                             give notes that carry no symbol tag a symbol, by hand
 *   chartnotes draw --symbol S --on candles,canslim|all
 *                                             draw the notes given S by hand on those dashboards only (all: every chart)
 *   pnl --period day|week|month|fy [--date D | --of D | --month YYYY-MM | --year YYYY-YY]
 *   pnl --rebuild                             re-match every stored trade
 *   statement --from D --to D --out FILE      one row per realized lot
 *   capture --month YYYY-MM                   what you took vs what was available
 *   profiles                                  the Groww accounts, and what each holds
 *   profiles rename --from A --to B           move an account's rows to a new name
 * </pre>
 *
 * <p>With more than one Groww account ({@code groww.profile.files}), every command takes {@code --profile NAME}.
 * Without it, what reads the broker or reports - {@code trades import --broker}, {@code trades balance},
 * {@code trades reasons}, {@code trades notes}, {@code pnl}, {@code statement} and {@code capture} - runs for every
 * account in turn, one failing account not stopping the rest; what records something by hand - {@code trades add},
 * {@code trades note}, {@code trades import --csv}, {@code trades balance --cash} - acts on the first account.
 */
public final class TradeJournalMain {

    public static void main(String[] args) {
        Args parsed = Args.parse(args);
        String command = parsed.command("pnl");

        try (AppContext context = new AppContext(AppConfig.load())) {
            switch (command) {
                case "trades" -> trades(context, parsed);
                case "chartnotes" -> chartNotes(context, parsed);
                case "pnl" -> pnl(context, parsed);
                case "statement" -> statement(context, parsed);
                case "capture" -> capture(context, parsed);
                case "profiles" -> profiles(context, parsed);
                default -> {
                    System.err.println("Unknown command: " + command);
                    System.err.println("Try: trades | chartnotes | pnl | statement | capture | profiles");
                    System.exit(2);
                }
            }
        }
    }

    private static void trades(AppContext context, Args args) {
        String subcommand = args.subcommand("import");
        switch (subcommand) {
            case "add" -> {
                eachProfile(context, args, false, profile -> addManualTrade(context, profile, args));
                return;
            }
            case "reasons" -> {
                eachProfile(context, args, true, profile -> reasons(context, profile, args));
                return;
            }
            case "note" -> {
                eachProfile(context, args, false, profile -> note(context, profile, args));
                return;
            }
            case "notes" -> {
                eachProfile(context, args, true, profile -> notes(context, profile, args));
                return;
            }
            case "balance" -> {
                eachProfile(context, args, !args.has("cash"), profile -> balance(context, profile, args));
                return;
            }
            default -> { }
        }

        LocalDate to = args.date("to", LocalDate.now(context.clock().zone()));
        LocalDate from = args.date("from", to.minusMonths(1));
        AppConfig config = context.config();

        // A CSV is one account's export, so it goes to one account; the broker is read for each.
        eachProfile(context, args, !args.has("csv"), profile -> {
            ExecutionDataClient client;
            if (args.has("csv")) {
                client = new CsvTradeImporter(Path.of(args.require("csv")), config.tradesCsvDateFormat(),
                        context.defaultProduct(), context.clock());
            } else {
                client = profile.brokerExecutionClient();
            }

            BrokerFillSync.Result result = profile.brokerFillSync().sync(client, from, to);
            System.out.printf("Imported %d new trades; matched %d closed lots, %d positions open, "
                            + "%d reasons attributed%n",
                    result.newTrades(), result.closedLots(), result.openPositions(), result.reasonsAttributed());
        });
    }

    /**
     * Runs {@code action} for the account {@code --profile} names, or else for every account ({@code everyByDefault})
     * or the first. Each account's output is headed by its name when there is more than one. With several, one that
     * fails is reported and the rest still run; the process then exits 1 so a scheduled job shows the failure.
     */
    private static void eachProfile(AppContext context, Args args, boolean everyByDefault,
                                    Consumer<ProfileContext> action) {
        List<ProfileContext> chosen;
        var named = args.value("profile");
        if (named.isPresent()) {
            var found = context.profile(named.get());
            if (found.isEmpty()) {
                System.err.printf("No profile named '%s'. The profiles are: %s%n", named.get(),
                        String.join(", ", context.profiles().stream().map(ProfileContext::name).toList()));
                System.exit(2);
                return;
            }
            chosen = List.of(found.get());
        } else {
            chosen = everyByDefault ? context.profiles() : List.of(context.primaryProfile());
        }
        boolean heading = context.profiles().size() > 1;
        List<String> failed = new java.util.ArrayList<>();
        for (ProfileContext profile : chosen) {
            if (heading) {
                System.out.printf("%n== %s ==%n", profile.name());
            }
            if (chosen.size() == 1) {
                action.accept(profile);
                continue;
            }
            try {
                action.accept(profile);
            } catch (RuntimeException e) {
                failed.add(profile.name());
                System.err.printf("%s: %s%n", profile.name(), e.getMessage());
            }
        }
        if (!failed.isEmpty()) {
            System.err.println("Failed for: " + String.join(", ", failed));
            System.exit(1);
        }
    }

    /**
     * {@code profiles}: each account, where its login comes from and what its journal holds. {@code profiles rename
     * --from A --to B} moves every row of account A to the name B, for when an env file's {@code GROWW_PROFILE_NAME}
     * changes; it refuses a name that already has rows, so two journals are never merged.
     */
    private static void profiles(AppContext context, Args args) {
        AccountProfileRepository repository = context.accountProfileRepository();
        if ("rename".equals(args.subcommand("list"))) {
            String from = args.require("from");
            String to = args.require("to");
            try {
                var moved = repository.rename(from, to);
                System.out.printf("Moved %s's rows to %s: %s%n", from, to, moved.isEmpty() ? "none" : moved);
                System.out.println("Set GROWW_PROFILE_NAME to the new name in that account's env file too, or the next "
                        + "start registers the old name again.");
            } catch (IllegalArgumentException e) {
                System.err.println(e.getMessage());
                System.exit(2);
            }
            return;
        }
        System.out.printf("%n%-3s %-20s %-8s %8s %6s %-11s %-11s %s%n", "#", "PROFILE", "LOGIN", "TRADES", "OPEN",
                "LAST FILL", "LAST BAL.", "CREDENTIALS FROM");
        for (var summary : repository.summaries()) {
            var configured = context.profile(summary.name()).map(ProfileContext::profile);
            System.out.printf("%-3s %-20s %-8s %8d %6d %-11s %-11s %s%n",
                    summary.position() == null ? "-" : String.valueOf(summary.position() + 1),
                    summary.name().isEmpty() ? "(none yet)" : summary.name(),
                    configured.map(p -> p.authMode()).orElse("-"), summary.trades(), summary.openPositions(),
                    summary.lastTradeDate() == null ? "-" : summary.lastTradeDate(),
                    summary.lastBalanceDate() == null ? "-" : summary.lastBalanceDate(),
                    configured.map(p -> p.source()).orElse("not configured now"));
        }
        System.out.println();
    }

    private static void addManualTrade(AppContext context, ProfileContext profile, Args args) {
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

        int inserted = profile.tradeJournalService()
                .importTrades((from, to) -> List.of(trade), trade.sessionDate(), trade.sessionDate());
        profile.tradeJournalService().rebuild();
        attributeReasons(profile, trade.sessionDate(), trade.sessionDate());
        System.out.println(inserted == 1 ? "Added." : "Already recorded; nothing changed.");
    }

    /**
     * Records the sentiment and the thinking behind a call, against every fill of it. Notes live in the
     * journal, so they survive re-imports, show next to the fill on the Candles and Calls dashboard, and
     * count as a reason on Decision Reasons ("did BULLISH calls pay?").
     */
    private static void note(AppContext context, ProfileContext profile, Args args) {
        LocalDate date = args.date("date", LocalDate.now(context.clock().zone()));
        TradeNoteService.Selection selection = new TradeNoteService.Selection(
                args.require("symbol").toUpperCase(Locale.ROOT),
                Side.valueOf(args.require("side").toUpperCase(Locale.ROOT)), date,
                args.value("at").map(LocalTime::parse).orElse(null), args.value("order").orElse(null));
        TradeNoteService service = profile.tradeNoteService();
        boolean clearing = args.flag("clear");

        TradeNoteService.Result result;
        try {
            result = clearing
                    ? service.clear(selection)
                    : service.note(selection, args.value("sentiment").orElse(null), args.value("text").orElse(null),
                            Instant.now().getEpochSecond());
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        attributeReasons(profile, date, date);

        System.out.printf("%s %d %s fills for %s on %s (%d %s):%n", clearing ? "Cleared the note from" : "Noted",
                result.fills().size(), selection.side(), selection.symbol(), date, result.calls().size(),
                result.calls().size() == 1 ? "order" : "orders");
        for (TradeNoteService.Call call : result.calls()) {
            System.out.printf("  %s  %d shares at %.2f  (%d fills, order %s)%n",
                    context.clock().timeOf(call.firstFillAt()).withNano(0), call.quantity(), call.averagePrice(),
                    call.fills(), call.orderId() == null ? "-" : call.orderId());
        }
        if (!clearing) {
            args.value("sentiment").ifPresent(sentiment -> System.out.println("Sentiment: " + sentiment.toUpperCase(Locale.ROOT)));
            args.value("text").ifPresent(text -> System.out.println("Note: " + text));
        }
        if (result.calls().size() > 1 && !args.has("at") && !args.has("order")) {
            System.out.println("That was more than one order. To note just one, add --at HH:MM or --order ID "
                    + "(and --clear removes a note).");
        }
    }

    /**
     * The notes written on the Grafana charts live in Grafana's own database; this keeps a copy with the journal.
     * A note's symbol comes from a tag that names an instrument, and its sentiment from the first tag that is a
     * generic one (UP counts as BULLISH); one with no symbol tag can be given a symbol by hand.
     */
    private static void chartNotes(AppContext context, Args args) {
        switch (args.subcommand("list")) {
            case "sync" -> {
                try {
                    var result = context.chartNoteSync().sync(Instant.now().getEpochSecond());
                    System.out.printf("Chart notes: %d in Grafana (%d new, %d tied to a symbol by a tag), %d no longer there%n",
                            result.found(), result.added(), result.withSymbol(), result.removed());
                    if (result.withSymbol() < result.found()) {
                        System.out.printf("%d have no symbol tag: see them with `chartnotes list`, and give them a symbol with "
                                + "`chartnotes assign --id N --symbol S`%n", result.found() - result.withSymbol());
                    }
                } catch (com.stockanalyzer.note.GrafanaAnnotationSource.Unavailable e) {
                    // Not an error for a scheduled run: Grafana may simply not be installed on this machine.
                    System.out.println("Chart notes not synced: " + e.getMessage());
                }
            }
            case "assign" -> {
                String known = knownSymbol(context, args.require("symbol"));
                for (String id : args.require("id").split(",")) {
                    boolean found = context.chartNoteRepository().assignSymbol(Long.parseLong(id.trim()), known);
                    System.out.println(found ? "Note " + id.trim() + " is now about " + known
                            : "No note " + id.trim() + " (run `chartnotes sync` first)");
                }
            }
            case "draw" -> {
                if (!args.has("symbol")) {
                    printLimits(context);
                    return;
                }
                String known = knownSymbol(context, args.require("symbol"));
                List<NoteDashboard> on;
                try {
                    on = NoteDashboard.parseList(args.require("on"));
                } catch (IllegalArgumentException e) {
                    System.err.println(e.getMessage());
                    System.exit(2);
                    return;
                }
                context.chartNoteRepository().limitTo(known, on.stream().map(NoteDashboard::uid).toList(),
                        Instant.now().getEpochSecond());
                System.out.println(on.isEmpty()
                        ? "The notes given " + known + " by hand are drawn on every chart again"
                        : "The notes given " + known + " by hand are now drawn on "
                                + on.stream().map(NoteDashboard::title).collect(Collectors.joining(", ")) + " only");
            }
            default -> {
                var found = context.chartNoteRepository()
                        .findLive(args.value("symbol").map(v -> v.toUpperCase(Locale.ROOT)).orElse(null));
                if (found.isEmpty()) {
                    System.out.println("No chart notes. Run `chartnotes sync` to copy them from Grafana.");
                    printLimits(context);
                    return;
                }
                System.out.printf("%n%-4s %-17s %-12s %-10s %-14s %s%n", "ID", "WHEN (IST)", "SYMBOL", "SENTIMENT", "TAGS", "NOTE");
                for (var note : found) {
                    System.out.printf("%-4d %-17s %-12s %-10s %-14s %s%n", note.grafanaId(),
                            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(
                                    Instant.ofEpochSecond(note.notedAt()).atZone(context.clock().zone())),
                            note.symbol() == null ? "(none)" : note.symbol(),
                            note.sentiment() == null ? "-" : note.sentiment(), String.join(",", note.tags()),
                            note.note() == null ? "" : note.note().replace('\n', ' '));
                }
                System.out.println();
                printLimits(context);
            }
        }
    }

    /** The instrument's symbol as the database spells it; exits for one that is not an instrument. */
    private static String knownSymbol(AppContext context, String typed) {
        String symbol = typed.toUpperCase(Locale.ROOT);
        String known = context.instrumentRepository().findAll().stream().map(i -> i.symbol())
                .filter(s -> s.equalsIgnoreCase(symbol)).findFirst().orElse(null);
        if (known == null) {
            System.err.println("Unknown symbol " + symbol + ": it is not one of the instruments in the database");
            System.exit(2);
        }
        return known;
    }

    private static void printLimits(AppContext context) {
        Map<String, List<String>> limits = context.chartNoteRepository().limits();
        if (limits.isEmpty()) {
            return;
        }
        System.out.println("Notes given a symbol by hand, drawn on some charts only (`chartnotes draw --symbol S --on all` undoes one):");
        limits.forEach((symbol, uids) -> System.out.printf("  %-12s %s%n", symbol,
                uids.stream().map(NoteDashboard::titleOf).collect(Collectors.joining(", "))));
        System.out.println();
    }

    private static void notes(AppContext context, ProfileContext profile, Args args) {
        LocalDate to = args.date("to", args.date("date", LocalDate.now(context.clock().zone())));
        LocalDate from = args.date("from", args.has("date") ? to : to.minusDays(14));
        List<TradeNoteService.Entry> entries = profile.tradeNoteService()
                .list(from, to, args.value("symbol").map(v -> v.toUpperCase(Locale.ROOT)).orElse(null));
        if (entries.isEmpty()) {
            System.out.printf("No noted calls from %s to %s.%n", from, to);
            return;
        }
        System.out.printf("%nNoted calls, %s to %s%n%n", from, to);
        System.out.printf("%-10s %-8s %-4s %-12s %8s %10s  %-12s %s%n", "DATE", "TIME", "SIDE", "SYMBOL", "SHARES",
                "AVG PRICE", "SENTIMENT", "NOTE");
        for (TradeNoteService.Entry entry : entries) {
            System.out.printf("%-10s %-8s %-4s %-12s %8d %10.2f  %-12s %s%n", entry.date(),
                    context.clock().timeOf(entry.firstFillAt()).withNano(0), entry.side(), entry.symbol(),
                    entry.quantity(), entry.averagePrice(), entry.sentiment() == null ? "-" : entry.sentiment(),
                    entry.note() == null ? "" : entry.note().replace('\n', ' '));
        }
        System.out.println();
    }

    private static void pnl(AppContext context, Args args) {
        eachProfile(context, args, true, profile -> pnl(context, profile, args));
    }

    private static void pnl(AppContext context, ProfileContext profile, Args args) {
        if (args.flag("rebuild")) {
            var result = profile.tradeJournalService().rebuild();
            System.out.printf("Rebuilt: %d closed lots, %d positions open%n",
                    result.lots().size(), result.openPositions().size());
            return;
        }

        PeriodType periodType = PeriodType.valueOf(args.value("period").orElse("day").toUpperCase());
        LocalDate anchor = anchorFor(context, args, periodType);
        String symbol = args.value("symbol").orElse(null);

        PnlSummary summary = profile.tradeJournalService().pnl(periodType, anchor, symbol);
        List<PnlSummary> breakdown = profile.tradeJournalService().breakdown(periodType, anchor, symbol);
        new ConsolePnlReporter().report(
                new PeriodBounds(context.config().financialYearStartMonth()).label(periodType, anchor),
                summary, breakdown, profile.tradeJournalService().openPositions());
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

    /** One file per account: the first account's at {@code --out}, another's beside it with its name added. */
    private static void statement(AppContext context, Args args) {
        LocalDate from = LocalDate.parse(args.require("from"));
        LocalDate to = args.date("to", LocalDate.now(context.clock().zone()));
        Path out = Path.of(args.value("out").orElse("statement.csv"));
        eachProfile(context, args, true, profile -> {
            Path file = profile.primary() || args.has("profile") ? out : besides(out, profile.name());
            List<RealizedLot> lots = profile.realizedLotRepository().findClosedBetween(from, to);
            new CsvStatementExporter(context.clock()).export(lots, file);
            System.out.println("Wrote " + file);
        });
    }

    /** statements/2026-10.csv for account "second" is statements/2026-10-second.csv. */
    static Path besides(Path file, String profile) {
        String slug = profile.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String renamed = dot > 0 ? name.substring(0, dot) + "-" + slug + name.substring(dot) : name + "-" + slug;
        return file.resolveSibling(renamed);
    }

    private static void capture(AppContext context, Args args) {
        eachProfile(context, args, true, profile -> capture(context, profile, args));
    }

    private static void capture(AppContext context, ProfileContext profile, Args args) {
        PeriodBounds bounds = new PeriodBounds(context.config().financialYearStartMonth());
        LocalDate anchor = args.value("month")
                .map(month -> LocalDate.parse(month + "-01"))
                .orElse(LocalDate.now(context.clock().zone()));
        LocalDate from = bounds.startOf(PeriodType.MONTH, anchor);
        LocalDate to = bounds.endOf(PeriodType.MONTH, anchor);

        List<Trade> trades = profile.tradeRepository().findRange(from, to);
        List<TradeAttribution> attributions = context.captureAnalyzer().analyse(trades, from, to);
        context.attributionRepository().upsertAll(attributions);
        new CaptureReporter().report(bounds.label(PeriodType.MONTH, anchor), attributions);
    }


    /** Recomputes why each trade in the range was taken. Safe to re-run. */
    private static int attributeReasons(ProfileContext profile, LocalDate from, LocalDate to) {
        List<Trade> trades = profile.tradeRepository().findRange(from, to);
        if (trades.isEmpty()) {
            return 0;
        }
        profile.tradeReasonRepository().deleteForTrades(trades.stream().map(Trade::id).toList());
        List<TradeReasonRepository.TradeReason> reasons = profile.reasonAttributor().attribute(trades);
        profile.tradeReasonRepository().upsertAll(reasons);
        return reasons.size();
    }

    private static void reasons(AppContext context, ProfileContext profile, Args args) {
        PeriodBounds bounds = new PeriodBounds(context.config().financialYearStartMonth());
        LocalDate anchor = args.value("month")
                .map(month -> LocalDate.parse(month + "-01"))
                .orElse(LocalDate.now(context.clock().zone()));
        LocalDate from = args.date("from", bounds.startOf(PeriodType.MONTH, anchor));
        LocalDate to = args.date("to", bounds.endOf(PeriodType.MONTH, anchor));

        int attributed = attributeReasons(profile, from, to);
        Map<String, Long> frequency = profile.tradeReasonRepository().findBetween(from, to).stream()
                .collect(Collectors.groupingBy(TradeReasonRepository.TradeReason::reasonCode,
                        TreeMap::new, Collectors.counting()));

        System.out.printf("%nWhy trades were taken, %s to %s (%d reasons across %d trades)%n%n",
                from, to, attributed, profile.tradeRepository().findRange(from, to).size());
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
    private static void balance(AppContext context, ProfileContext profile, Args args) {
        LocalDate date = args.date("date", LocalDate.now(context.clock().zone()));

        if (args.value("cash").isPresent()) {
            double cash = Double.parseDouble(args.require("cash"));
            Double invested = args.value("invested").map(Double::parseDouble).orElse(null);
            profile.accountBalanceRepository().record(date, cash, invested, "manual");
            System.out.printf("Recorded balance for %s by hand: cash %s%s%n", date, rupees(cash),
                    invested == null ? "" : ", invested " + rupees(invested));
            return;
        }

        AccountSnapshot snapshot = profile.accountDataClient().fetch();
        profile.accountBalanceRepository().record(date, snapshot);
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
