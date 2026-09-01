# Dashboards

Three tabs over the same SQLite file the analyzer writes. No exporter, no API,
no second copy of the data — Grafana reads `data/stock-analyzer.db` directly
through the [SQLite datasource plugin](https://grafana.com/grafana/plugins/frser-sqlite-datasource/).

## Run it

**Without Docker** (nothing installed system-wide):

```bash
./grafana/run-local.sh      # http://localhost:3000, admin/admin
```

Downloads the standalone Grafana build into `grafana/.runtime/` the first time
(about 100 MB), installs the SQLite plugin there, rewrites the datasource path
to point at this repo's database, and starts it. Delete `grafana/.runtime/` to
undo everything. Ctrl-C stops it.

**With Docker**, if you have it:

```bash
cd grafana && docker compose up -d
```

Either way you get the plugin, the datasource and all three dashboards in a
"Stock Analyzer" folder. Under Docker the database is mounted read-only, so a
dashboard can never modify the journal; running locally, open it read-only if
the daemon is live.

## Troubleshooting

**`Grafana-server Init Failed: Could not find config defaults, make sure
homepath command line parameter is set or working directory is homepath`**

The `grafana cli` command shares the server's startup path, so it needs
`--homepath` too — not just `grafana server`. Every invocation wants it:

```bash
RUNTIME=grafana/.runtime
HOME_PATH=$RUNTIME/grafana-v11.6.0
$HOME_PATH/bin/grafana cli --homepath "$HOME_PATH" \
    --pluginsDir "$RUNTIME/plugins" plugins install frser-sqlite-datasource
```

`run-local.sh` passes it. If you hit this after a failed first run, the script
had aborted before writing `custom.ini`, leaving a half-built runtime — delete
`grafana/.runtime/` and run it again.

**Port 3000 already in use:** `lsof -ti:3000 | xargs kill`, or set
`GF_SERVER_HTTP_PORT`.

**Panels say "no data":** check the datasource first —
Connections → Data sources → StockAnalyzer → Save & test should say *Data source
is working*. If it does, the tables are simply empty; see below.

## The dashboards need data before they show anything

They are views over the tables, so an empty table is an empty panel — not a bug.
What each tab needs:

| Tab | Needs | Command |
|---|---|---|
| Candles and Calls | sessions (weeks of them, for the weekly panel) | `DailyAnalysisMain backfill --from ... --to ...` |
| Candles and Calls, markers | trades | `TradeJournalMain trades import` or `trades add` |
| Account Overview | trades, and a balance for a true account value | `trades import`, `trades balance --cash ...` |
| Decision Reasons | trades with reasons attached | `trades reasons --month ...` |

With a single session ingested and no trades, expect one candle in the daily
panel, one bar in the weekly panel, and two empty tabs.

## The three tabs

**Account Overview** — money in the account, total staked, gains and losses, and
what it all cost. Four stat tiles across the top, then the account value over
time, per-day gains/losses and bet amounts side by side, taxes and charges
stacked, and finally every fill sorted by bet amount descending.

The account value line is worth understanding before trusting it: *recorded
cash* is whatever you last told the journal (`trades balance --cash`), *cumulative
net P&L* is derived from closed lots, and *account value* adds them. If you have
never recorded a balance, the line is P&L alone — a different claim from "this is
what the account is worth", which is why all three are plotted rather than one.

**Candles and Calls** — pick a symbol; daily candles on top, weekly below, both
marked with the buys (blue) and sells (orange) you actually took, plotted at the
average fill price for that session or week. Underneath, every call with its
reason attached.

**Decision Reasons** — how often each reason recurs, where it came from (typed by
you, an event the detector found, an alert that fired, or the model), buy
reasons versus sell reasons, and a table of whether each reason actually paid.
`UNEXPLAINED` is a real category, not a gap: a fill with no note, event or alert
behind it is worth counting.

One number to read carefully: the overview's charges are **all charges paid on
that day's fills**, while `pnl`'s charges are only those **allocated to lots that
closed**. Both are right; they answer different questions, and they differ
whenever a position is still open.

## Where the numbers come from

Every panel reads a view, not a hand-written join — `v_bet`, `v_daily_pnl`,
`v_equity_curve`, `v_daily_candles`, `v_weekly_candles`, `v_trade_markers`,
`v_reason_frequency`. They are created by schema migration v4 (see
`SchemaMigrator`), so the reporting logic lives in one place instead of being
retyped into a dozen panels, and `DashboardViewsTest` checks what each one
returns.

`GrafanaDashboardQueryTest` executes every query in every dashboard JSON against
a real migrated database on each build. A typo in dashboard SQL fails the build
rather than turning into an empty panel nobody investigates.

## Feeding the dashboards

```bash
# Overview needs trades, and a balance if you want a true account value
java -cp ../target/stock-analyzer.jar com.stockanalyzer.TradeJournalMain \
     trades import --broker --from 2026-08-01 --to 2026-08-28
java -cp ../target/stock-analyzer.jar com.stockanalyzer.TradeJournalMain \
     trades balance --cash 250000 --invested 50000

# Candles need sessions
java -cp ../target/stock-analyzer.jar com.stockanalyzer.DailyAnalysisMain \
     backfill --from 2026-06-01 --to 2026-08-28

# Reasons are attributed on import, and can be recomputed any time
java -cp ../target/stock-analyzer.jar com.stockanalyzer.TradeJournalMain \
     trades reasons --month 2026-08
```

Panels are not time-filtered in SQL — they return the full series and Grafana's
time range does the framing. If you accumulate years of one-minute history and a
panel gets slow, add `WHERE session_date >= date($__unixEpochFrom(), 'unixepoch')`
to that panel's query.
