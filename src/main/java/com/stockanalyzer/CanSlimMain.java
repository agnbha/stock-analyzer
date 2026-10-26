package com.stockanalyzer;

import com.stockanalyzer.canslim.CanSlimReport;
import com.stockanalyzer.canslim.ScreenerException;
import com.stockanalyzer.config.AppConfig;
import com.stockanalyzer.report.CanSlimReporter;
import com.stockanalyzer.util.Args;

/**
 * CAN SLIM entry point: screen for growth stocks, then read each one's chart
 * against O'Neil's buy and sell rules.
 *
 * <pre>
 *   scan                       run the configured screener.in query and score the results
 *   scan --query "..."         a different query for this run
 *   scan --no-screener         holdings and the configured universe only: sell signals, no new candidates
 *   ... --all                  list every scored stock, not only those with a signal
 * </pre>
 *
 * Exit status 3 means the screener.in session needs renewing - the one failure
 * a scheduled run cannot fix by retrying.
 */
public final class CanSlimMain {

    public static void main(String[] args) {
        Args parsed = Args.parse(args);
        String command = parsed.command("scan");
        if (!command.equals("scan")) {
            System.err.println("Unknown command: " + command);
            System.err.println("Try: scan [--query \"...\"] [--no-screener] [--all]");
            System.exit(2);
        }

        AppConfig config = AppConfig.load();
        String query = parsed.flag("no-screener") ? null
                : parsed.value("query").orElse(config.canSlimQuery());
        if (!parsed.flag("no-screener") && (query == null || query.isBlank())) {
            System.err.println("No screener query: set canslim.screener.query, pass --query, or use --no-screener.");
            System.exit(2);
        }

        try (AppContext context = new AppContext(config)) {
            CanSlimReport report = context.canSlimService().scan(query);
            new CanSlimReporter(System.out).report(report, parsed.flag("all"));
        } catch (ScreenerException e) {
            System.err.println(e.getMessage());
            System.exit(e.needsLogin() ? 3 : 1);
        }
    }
}
