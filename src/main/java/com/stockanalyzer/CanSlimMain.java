package com.stockanalyzer;

import com.stockanalyzer.canslim.CanSlimReport;
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
 * Without a screener.in session the query is skipped (noted in the report), and
 * holdings and the configured symbols are scored from public company pages.
 * Exit status 3 means a configured session was rejected and needs renewing; 1,
 * that the query failed for another reason. Either way the rest of the scan ran.
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
            // The scan has been stored and printed either way. A query that failed after a session was
            // configured is the one thing a person has to act on, so it leaves a status behind.
            if (report.screenerFailed()) {
                System.exit(report.needsLogin() ? 3 : 1);
            }
        }
    }
}
