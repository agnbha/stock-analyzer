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

## Notes on the charts: documenting a call

Notes on a chart are Grafana annotations. To write one:

1. **Click** a point on a candle chart. The tooltip pins and shows an **Add annotation** button.
2. Press it, write the note - why you are in or out, what you expect, the plan - and add two **tags**:
   - the **symbol**, in capitals, spelled as in the symbol picker (`RELIANCE`, `M&M`, `BAJAJ-AUTO`), and
   - a **sentiment**: `BULLISH`, `BEARISH` or `NEUTRAL`. (`BUY` or `SELL` too, if you like; other words such as
     `NERVOUS` are kept but are not read as a market view.)
3. **Save.** The note appears at once as a cyan dashed line; hover its marker to read it, and use the pencil and bin
   in the hover box to edit or delete it.

The symbol tag is what makes a note show: it is drawn on every chart of that symbol - Candles and Calls, Stock
Trend and CAN SLIM - wherever you wrote it, and on Account Overview and Decision Reasons (which have no symbol
picker) when it is tagged `BUY` or `SELL`. A note without the symbol tag is saved but not drawn, which keeps another
symbol's notes off the candles; tags are matched exactly, so `reliance` will not show.

**Your notes are kept in the analyzer's database too.** Grafana stores them in its own file, so they were not in the
journal, and would be lost with Grafana's volume. `chartnotes sync` copies them into the `chart_note` table
(`evening.sh` runs it each night; run it by hand to see a note straight away). The table on **Candles and Calls**
(*notes written on the charts*) lists every note for the symbol, newest first, plus any with no symbol tag, so a note
can be found even when it is not drawn. A note edited in Grafana is updated on the next sync, and one deleted there is
marked as deleted, not lost.

**Generic sentiments.** A note is counted under the first tag that is `BULLISH`, `BEARISH` or `NEUTRAL`, and the
home-made labels that stand for them count as the same: `UP`, `UPTREND` and `LONG` are `BULLISH`; `DOWN`, `DOWNTREND`
and `SHORT` are `BEARISH`; `LEVEL`, `FLAT`, `SIDEWAYS` and `RANGE` are `NEUTRAL`. The mapping is `notes.sentiment.aliases`.
Grafana's own tags are never rewritten: a note tagged `UP` stays `UP` there and is stored as `BULLISH` here.

**A note with no symbol tag** (one written before the symbol tag was needed) is not drawn, and cannot be edited from the
chart. List them with `chartnotes list`, and give each a symbol with `chartnotes assign --id 3 --symbol RELIANCE`: it is
then drawn on that symbol's charts, from the analyzer's copy.

To record *why* a call was made against the fills themselves, use `trades note` (see the main README). The call is
drawn on the candle charts for you, as a light-blue (buy) or orange (sell) dashed marker with the sentiment and your
note on hover; the same words appear on the **every call** table, and the sentiment is a reason on Decision Reasons.
Those markers come from the journal, so the pencil and bin in their hover box do nothing: run `trades note` again to
change one.

**Why the built-in annotation layer is the notes layer, and invisible as a switch.** Grafana offers *Add annotation*
only while a dashboard's built-in "Annotations & Alerts" layer is enabled, and only that layer redraws the moment you
save. So each dashboard keeps it enabled, hides its switch, and gives it a tag filter (the symbol) instead of "every
note made on this dashboard". Do not switch it off to tidy the chart: you could no longer annotate.

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
| CAN SLIM | a scan | `CanSlimMain scan` (or the nightly `evening.sh`) |

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

The minute-candle chart on this page draws at most **200** candles unless you change the **Max candles** box at the
top (type a number, press Enter). When the sessions you pick hold more minutes than that, minutes are combined into
longer candles so the chart still covers all of them: 4- to 6-minute candles for two or three days, 2-minute for a
single session, and about three candles a day (a couple of hours each) across three months. With room to spare it is
one candle per minute.
A candle opens where its first minute opened, closes where its last closed, and spans the highest high, lowest low
and total volume of its minutes; VWAP and the averages are read as they stand at its end, and your buys, sells and the
detector's windows sit on the candle they fall in (an average, if several do). A candle never spans two sessions, and
the total never exceeds the number typed. A blank, non-numeric, zero or negative entry means 200. The weekly chart is
not capped: it draws one candle a week over the time range picked at the top.

One thing the box cannot change: the chart's time axis always spans the time range picked at the top. A single
session shown on a three-month range is a thin strip at the right edge however many candles it has, so narrow the
range to the sessions you want to look at.

**Decision Reasons** — how often each reason recurs, where it came from (typed by
you, an event the detector found, an alert that fired, or the model), buy
reasons versus sell reasons, and a table of whether each reason actually paid.
`UNEXPLAINED` is a real category, not a gap: a fill with no note, event or alert
behind it is worth counting.

**CAN SLIM** — the market's direction and distribution-day count across the
top, then two lists: breakouts and setups about to break out (stocks that passed
the screener.in query), and rallies that have ended (stop losses, heavy-volume
breaks of the 50-day average, climax runs). Click a symbol for its daily chart
with the 50- and 200-day averages, the pivot and every scan's reading of it; the
full score sheet, letter by letter, is at the bottom.

**Watchlist** — market breadth, how many tracked stocks read bullish, a shortlist of the five highest trend scores,
the shortlist's own scorecard, and the stocks sitting in the bottom decile of their range or of value traded.

*The shortlist is a list to look at, not a forecast.* It ranks complete sessions only, as of the last full session, so
a session still in progress (or one ingested half-way) can no longer decide the order. Beside each symbol are the
stock's typical moves from its own open over its last 20 sessions: how far above and below the open it usually goes,
and the share of days it got 1% either way. A 0.6% or 1% sell target (`alerts.sell.targets.pct`) and a stop belong
against those numbers, not against a fixed figure.

*The scorecard underneath is the shortlist marked against what happened.* Each evening `evening.sh` writes the
shortlist down (`DailyAnalysisMain watchlist`; `watchlist.top`, 5 by default). The scorecard reads the pick's next complete
session from the stored sessions, so a session that is later re-ingested corrects its own result. It reports the
open-to-close and close-to-close move of the picks, each against the same day's average over every tracked symbol, and
how often a pick dipped 1% below its open. Evenings before the first live run are rebuilt from stored history
(`watchlist-replay`, marked as such), so the record starts on day one; a replay never rewrites an evening that was
logged live.

**What was measured, and why the page makes no promise.** On a year of daily bars for the liquid market (November 2025 to November 2026: 246
sessions, about 770 names a session, 181,798 stock-sessions; turnover of at least ₹10 Cr), the score did not separate
the next day's winners from its losers:

| | next session, relative to the average stock |
|---|---|
| every fifth of the market by score, weakest to strongest | within ±0.05 points of the average, standard errors 0.01–0.03 |
| the top 5 of the whole market | −0.04 ± 0.13 points close to close |
| strongest fifth falling more than 4% the next day | 6.6% of cases, against 4.4% for the market: a more volatile list, not a better one |

Nothing tried on top of it rescued the one-day horizon: capping the run-up, an RSI ceiling, yesterday's volume and
where it closed in its range all landed inside the noise. Neither did looking at the open itself. For the 62 tracked
symbols (5,982 stock-sessions of minute data), the state at 09:45 (above the first 15 minutes' high and VWAP, inside the
range, or below it) left the rest of the day within ±0.06 points of the average whichever it was; among the strongest fifth of
the whole market, those that gapped up more than 2% did fade from the open to the close (−0.38 ± 0.14 points), but
waiting 30 minutes to look did not select better entries. Screened at 1, 5, 10 and 20 sessions, the inputs that point the right way are medium-term
momentum and closeness to the 120-day high at 10–20 sessions, at about one standard error with the same sign in both
halves of the year: not yet distinguishable from chance, and the horizon the CAN SLIM page already covers.

So the scorecard exists to settle the question with evidence as it accumulates, instead of with a story about the last
pick. The scripts that produced the numbers are in `scripts/research/` and open the database read-only
(`python3 -I scripts/research/market_backtest.py [path/to/stock-analyzer.db]`); rerun them as the history grows.

**Last data refresh** — a one-line label in the top-right corner of every page
except CAN SLIM (which has its own *Last scan*), showing how long ago the page's
data was last written. It is a small stat panel without a title, so it takes one
row and sits beside or above the first panel. What it counts differs by page:

| Page | Newest of |
|---|---|
| Account Overview | last fill imported, last balance recorded, last reasons worked out |
| Candles and Calls | last live candle (daemon, every poll in market hours), last session ingested, last fill imported |
| Decision Reasons | when the reasons were last worked out (each fill import) |
| Stock Trend, Watchlist | when a session was last written to the history, which happens after the close; during the market day it shows that earlier write, not the live session |

On Candles and Calls, a figure much older than a few minutes during market hours
means the daemon is not running or cannot reach Groww.

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
