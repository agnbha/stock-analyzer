# Dashboards

Three tabs over the same SQLite file the analyzer writes. No exporter, no API,
no second copy of the data — Grafana reads `data/stock-analyzer.db` directly
through the [SQLite datasource plugin](https://grafana.com/grafana/plugins/frser-sqlite-datasource/).

## Run it

```bash
cd grafana
docker compose up -d      # http://localhost:3000, admin/admin
```

That installs the plugin, provisions the datasource, and loads all three
dashboards into a "Stock Analyzer" folder. The database is mounted read-only, so
a dashboard can never modify the journal.

Running Grafana natively instead:

```bash
grafana cli plugins install frser-sqlite-datasource
# then point provisioning/datasources/sqlite.yaml at the absolute path of
# data/stock-analyzer.db and copy provisioning/ + dashboards/ into your
# Grafana config directory
```

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
