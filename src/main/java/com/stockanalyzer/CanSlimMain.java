package com.stockanalyzer;

import com.stockanalyzer.canslim.CanSlimReport;
import com.stockanalyzer.canslim.CompanyProfileService;
import com.stockanalyzer.config.AppConfig;
import com.stockanalyzer.report.CanSlimReporter;
import com.stockanalyzer.util.Args;

import java.util.List;
import java.util.Locale;

/**
 * CAN SLIM entry point: screen for growth stocks, then read each one's chart
 * against O'Neil's buy and sell rules.
 *
 * <pre>
 *   scan                       run the configured screener.in query and score the results
 *   scan --query "..."         a different query for this run
 *   scan --no-screener         holdings and the configured universe only: sell signals, no new candidates
 *   ... --all                  list every scored stock, not only those with a signal
 *   ... --market               also price and rank every listed NSE/BSE equity (canslim.market.enabled=true
 *                              does this on every scan; --no-market skips it for one)
 *   ... --limit N              with --market, only about N listings spread through the list: a trial run
 *   ... --top N                list the N strongest stocks by RS rating (50 for a market scan)
 *   profiles                   read the tracked symbols' screener.in pages for the Candles and Calls page's
 *                              company details: the stalest first, at most company.profile.max.pages.per.run
 *   profiles --symbol S        one company, now, whatever its copy's age
 *   profiles --max-pages N     a different budget for this run; 0 reads every company that is due
 * </pre>
 *
 * Without a screener.in session the query is skipped (noted in the report), and
 * holdings and the configured symbols are scored from public company pages.
 * Exit status 3 means a configured session was rejected and needs renewing; 1,
 * that the query failed for another reason. Either way the rest of the scan ran.
 */
public final class CanSlimMain {

    public static void main(String[] args) {
        Args parsed = Args.parse(args);
        String command = parsed.command("scan");
        if (command.equals("profiles")) {
            profiles(parsed);
            return;
        }
        if (!command.equals("scan")) {
            System.err.println("Unknown command: " + command);
            System.err.println("Try: scan [--query \"...\"] [--no-screener] [--market] [--limit N] [--top N] [--all]"
                    + " | profiles [--symbol S] [--max-pages N] [--max-other-pages N]");
            System.exit(2);
        }

        AppConfig config = AppConfig.load();
        String query = parsed.flag("no-screener") ? null
                : parsed.value("query").orElse(config.canSlimQuery());
        if (!parsed.flag("no-screener") && (query == null || query.isBlank())) {
            System.err.println("No screener query: set canslim.screener.query, pass --query, or use --no-screener.");
            System.exit(2);
        }

        boolean market = parsed.flag("market") || (config.canSlimMarketEnabled() && !parsed.flag("no-market"));
        try (AppContext context = new AppContext(config)) {
            CanSlimReport report = context.canSlimService().scan(query,
                    market ? context.canSlimMarket(parsed.integer("limit", 0)) : null);
            new CanSlimReporter(System.out).report(report, parsed.flag("all"),
                    parsed.integer("top", market ? config.canSlimMarketTop() : 0));
            // The scan has been stored and printed either way. A query that failed after a session was
            // configured is the one thing a person has to act on, so it leaves a status behind.
            if (report.screenerFailed()) {
                System.exit(report.needsLogin() ? 3 : 1);
            }
        }
    }

    /**
     * Refreshes the company profiles the Candles and Calls page shows. A run that read nothing because
     * screener.in refused leaves a failing status for the evening log; one that simply had nothing due, or
     * stopped after reading some, does not: what is left is picked up on a later night.
     */
    private static void profiles(Args parsed) {
        AppConfig config = AppConfig.load();
        boolean one = parsed.has("symbol");
        try (AppContext context = new AppContext(config)) {
            List<String> symbols = one ? List.of(parsed.require("symbol").toUpperCase(Locale.ROOT))
                    : config.stockSymbols();
            int maxPages = parsed.integer("max-pages", one ? 0 : config.companyProfileMaxPagesPerRun());
            CompanyProfileService.Result result = context.companyProfileService()
                    .refresh(symbols, maxPages, config.companyProfileRefreshDays() * 86_400L, one);
            System.out.printf("Company profiles: %d read, %d with nothing on screener.in, %d still due%n",
                    result.fetched(), result.withoutProfile(), result.notAttempted());
            if (result.stoppedBy() != null) {
                System.out.println("Stopped early: " + result.stoppedBy());
                if (result.fetched() == 0 && result.withoutProfile() == 0) {
                    System.exit(1);
                }
                return;
            }
            if (!one) {
                untrackedProfiles(parsed, config, context, symbols);
            }
        }
    }

    /**
     * The slow round over every other symbol the database holds, a few pages a night: stocks you have traded
     * first, then the never-read, then the oldest copies, each read again after three months. Not read when
     * screener.in refused the first round; a refusal here only ends the round, since the rest is picked up
     * on a later night, so it never fails the evening log.
     */
    private static void untrackedProfiles(Args parsed, AppConfig config, AppContext context, List<String> tracked) {
        int maxPages = parsed.integer("max-other-pages", config.companyProfileUntrackedMaxPagesPerRun());
        if (maxPages <= 0) {
            return;
        }
        List<String> traded = context.tradeRepository().findAllOrdered().stream()
                .map(trade -> trade.symbol()).distinct().toList();
        CompanyProfileService.Result result = context.companyProfileService().refreshUntracked(tracked, traded,
                maxPages, config.companyProfileUntrackedRefreshDays() * 86_400L);
        System.out.printf("Company profiles of other symbols: %d read, %d with nothing on screener.in, %d still due%n",
                result.fetched(), result.withoutProfile(), result.notAttempted());
        if (result.stoppedBy() != null) {
            System.out.println("Stopped early: " + result.stoppedBy());
        }
    }
}
