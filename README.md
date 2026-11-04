# Stock Analyzer

A personal intraday analysis, alerting and trade-journal system for NSE
equities, built on the [Groww Trading API](https://groww.in/trade-api/docs).
Seven parts, sharing one local database and one market-hours process:

| Part | What it does | Runs |
|---|---|---|
| 1 | Daily intraday analysis: the top 3 gain windows per symbol per session, appended to months of history | nightly, batch |
| 2 | A time-of-day prior, and a hook for a trained model | offline, plus online scoring |
| 3 | One alert: after a purchase, when to sell and at what limit price | during market hours |
| 4 | Live monitoring every 2-3 minutes, projecting new candles as they arrive | during market hours |
| 5 | Trade journal and gain/loss statements per day, week, month and financial year | on demand |
| 6 | Grafana dashboards over the same database | on demand |
| 7 | CAN SLIM: screener.in fundamentals plus daily-chart buy and sell rules | nightly, batch |

The full design, including why each decision was made, is in
[docs/DESIGN.md](docs/DESIGN.md).

> This produces statistics, signals, notifications and record-keeping. It places
> no orders. Part 7's buy and sell signals are readings of William O'Neil's
> published rules against the data, not recommendations. Part 5's P&L is for
> your own review, not a tax filing - your broker's contract note remains
> authoritative.

## Authentication

Two flows, selected with `groww.auth.mode`:

Each flow uses the pair Groww issues for it, under Groww's own names
([groww.in/trade-api/api-keys](https://groww.in/trade-api/api-keys)):

| Flow | Groww calls them | Setting | Environment variable |
|---|---|---|---|
| `checksum` | API key | `groww.api.key` | `GROWW_API_KEY` |
| | API secret | `groww.api.secret` | `GROWW_API_SECRET` |
| `totp` | TOTP token | `groww.totp.token` | `GROWW_TOTP_TOKEN` |
| | TOTP secret | `groww.totp.secret` | `GROWW_TOTP_SECRET` |

```properties
# API key + API secret, signed as SHA-256(secret + timestamp). Groww wants a fresh
# approval of the key each day.
groww.auth.mode=checksum

# TOTP token + a current six-digit code made from the TOTP secret. The token is what
# Groww's SDK passes as api_key. No daily approval.
groww.auth.mode=totp
```

The values go in `~/.stock-analyzer.env`, not in the properties file, which is committed
to the repository and bundled into the jar (a test fails the build if one is put back there).
The TOTP secret is the Base32 seed shown next to the token — the string an authenticator
app scans — not the six digits it displays, and it is never sent. Codes are minted per
request and never cached, so **the machine's clock has to be roughly right**: on a server,
keep NTP running, or tokens will be rejected with what looks like a bad-credential error.
A TOTP setup from before `groww.totp.token` existed, with the token in `groww.api.key`,
still works while the new setting is blank.

## Requirements

- Java 25+
- Maven 3.8+
- A Groww Trading API key + secret ([generate one here](https://groww.in/trade-api/api-keys))
- Docker, for the Grafana dashboards (optional)
- A model-serving endpoint, only if you turn Part 2's model on (optional - see
  [docs/MODEL-CONTRACT.md](docs/MODEL-CONTRACT.md))

## Build

```bash
mvn clean package
```

## Commands

Everything writes to one SQLite file, `data/stock-analyzer.db` by default.

### Part 1 - analysis

```bash
CP=target/stock-analyzer.jar
# JDK 24+ warns about sqlite-jdbc's native library unless it is allowed (the scheduled jobs pass this too)
JOPTS=--enable-native-access=ALL-UNNAMED

# yesterday's session for every configured symbol
java $JOPTS -cp $CP com.stockanalyzer.DailyAnalysisMain daily
java $JOPTS -cp $CP com.stockanalyzer.DailyAnalysisMain daily --date 2026-08-27

# seed the previous months (same code path, just a wider range)
java $JOPTS -cp $CP com.stockanalyzer.DailyAnalysisMain backfill --from 2026-06-01 --to 2026-08-27

# re-run a changed detector over stored candles, no API calls
java $JOPTS -cp $CP com.stockanalyzer.DailyAnalysisMain recompute --from 2026-06-01

# read the accumulated history back
java $JOPTS -cp $CP com.stockanalyzer.DailyAnalysisMain report --symbol RELIANCE --months 6
java $JOPTS -cp $CP com.stockanalyzer.DailyAnalysisMain export --from 2026-06-01 --out opportunities.csv
```

### Part 2 - the time-of-day prior

```bash
java $JOPTS -cp $CP com.stockanalyzer.DailyAnalysisMain hot-windows --lookback 60
java $JOPTS -cp $CP com.stockanalyzer.DailyAnalysisMain evaluate --date 2026-08-27
```

### Parts 3 and 4 - alerts and live monitoring

```bash
java $JOPTS -cp $CP com.stockanalyzer.MarketDayDaemon            # run for today
java $JOPTS -cp $CP com.stockanalyzer.MarketDayDaemon --dry-run  # console only, no notifications
java $JOPTS -cp $CP com.stockanalyzer.MarketDayDaemon status     # is it alive?
```

**The sell alert.** There is one alert: when you buy something, it says when to
sell. Everything you bought in a symbol today has one average price, weighted by
quantity, so buys at different prices are combined. The first time the price
reaches each target above it (0.6% and 1% by default, `alerts.sell.targets.pct`)
you get one alert with the limit price to set:

```
BHARTIARTL is up 0.6% on your average buy of 2065.00 (100 shares). Sell now: set the limit at 2077.40.
```

- The limit is the target rounded **up** to the next tick (`alerts.sell.tick.size`,
  0.05), so the order is never below the target, and the alert comes when the
  price has actually reached it: an order at that limit can fill.
- Buying more changes the average, so the targets move and each new average gets
  its own alerts. Selling the position out ends them; a part-sold position still
  alerts, for the shares that are left.
- It works from the fills the daemon imports every few minutes
  (`trades.live.import.interval.minutes`), so an alert follows a purchase by up
  to that long. A symbol you bought that is not in `stock.symbols` is polled for
  the rest of the day, so it is watched too.
- A target the price touched more than `alerts.max.age.minutes` ago (10) is not
  announced, which keeps a restart from announcing the morning; one the price is
  still at is. Each alert is sent once, and a restart does not repeat it.

Nothing else alerts. Market events and model predictions are still detected,
stored and scored (trades are attributed to them, and the next day's evaluation
reads them), but they no longer interrupt you, and there are no session or
"strong window" alerts, caps or symbol filters.

**Desktop notifications.** Alerts go to the console and, on macOS, to a
notification banner (`alerts.sinks=console,macos`). Sell alerts are `URGENT`, so
the banner also plays a sound. `alerts.desktop.min-severity` sets the lowest
severity that reaches the desktop; drop `macos` from `alerts.sinks` to turn the
banners off. `--dry-run` never posts one.

osascript posts the banner as *Script Editor*, so macOS shows nothing until that
app is allowed to notify (System Settings > Notifications > Script Editor, and
check that Focus modes are not hiding it). To test the channel without waiting
for a market event:

```bash
osascript -e 'display notification "test" with title "stock-analyzer" sound name "Glass"'
```

A banner that fails to post is logged as `Desktop notification failed` in
`logs/market-day-*.err.log`. The settings are read when the daemon starts, so a
change takes effect on the next launch.

### Part 5 - the trade journal

```bash
java $JOPTS -cp $CP com.stockanalyzer.TradeJournalMain trades import --broker --from 2026-08-01 --to 2026-08-28
java $JOPTS -cp $CP com.stockanalyzer.TradeJournalMain trades import --csv contract-notes.csv
java $JOPTS -cp $CP com.stockanalyzer.TradeJournalMain trades add --symbol RELIANCE --side BUY \
     --qty 10 --price 1418.20 --at 2026-08-28T09:47 --product MIS --reason "VWAP reclaim"
java $JOPTS -cp $CP com.stockanalyzer.TradeJournalMain trades balance                    # read it from the broker
java $JOPTS -cp $CP com.stockanalyzer.TradeJournalMain trades balance --cash 250000        # fallback: enter it by hand
java $JOPTS -cp $CP com.stockanalyzer.TradeJournalMain trades reasons --month 2026-08
java $JOPTS -cp $CP com.stockanalyzer.TradeJournalMain trades note --symbol BHARTIARTL --side BUY \
     --sentiment BULLISH --text "Results beat; sector strong. Plan: sell half at +1%"
java $JOPTS -cp $CP com.stockanalyzer.TradeJournalMain trades notes --date 2026-11-04

java $JOPTS -cp $CP com.stockanalyzer.TradeJournalMain pnl --period day   --date  2026-08-28
java $JOPTS -cp $CP com.stockanalyzer.TradeJournalMain pnl --period week  --of    2026-08-28
java $JOPTS -cp $CP com.stockanalyzer.TradeJournalMain pnl --period month --month 2026-08
java $JOPTS -cp $CP com.stockanalyzer.TradeJournalMain pnl --period fy    --year  2026-27
java $JOPTS -cp $CP com.stockanalyzer.TradeJournalMain pnl --rebuild
java $JOPTS -cp $CP com.stockanalyzer.TradeJournalMain statement --from 2026-04-01 --out statement.csv
java $JOPTS -cp $CP com.stockanalyzer.TradeJournalMain capture --month 2026-08
```

**Documenting a call.** Calls are made by hand, so what you were thinking when you made one is
worth writing down while you remember it. `trades note` records a sentiment and some words against
a call:

```bash
trades note --symbol BHARTIARTL --side BUY --sentiment BULLISH --text "Results beat; sector strong"
trades note --symbol BHARTIARTL --side BUY --at 09:45 --sentiment NERVOUS     # the second purchase only
trades note --symbol BHARTIARTL --side SELL --order ORDER_ID --text "Took it at +0.6%"
trades note --symbol BHARTIARTL --side BUY --at 09:45 --clear                 # take a note off again
trades notes --date 2026-11-04                                                 # what you have noted
```

- A call is usually many fills (one order arrives as dozens), so the note is written against every
  fill of the call. `--symbol`, `--side` and the day (`--date`, default today) pick the call; `--at
  HH:MM` (fills within three minutes of it) or `--order` narrows it when the day had more than one. If
  you do not narrow it, every purchase that day gets the note, and the command lists which ones.
- The sentiment is a label of your choosing, upper-cased (`BULLISH`, `BEARISH`, `NERVOUS`, `CALM`).
  The text is free. Give either, or both. Writing a note again replaces it.
- Notes live in the journal, so they survive re-importing fills. They appear in three places:
  - **On the candle charts**, as a dashed marker at the time of the call - light blue for a buy, orange for a
    sell, matching "Your buy" and "Your sell". Hover it for `WIPRO BUY 5000 @ 195.55 - BULLISH: your words`.
    They show on Candles and Calls, Stock Trend and CAN SLIM for that symbol, and on Account Overview and
    Decision Reasons for every symbol. The pencil and bin in the hover box belong to Grafana's own notes and do
    nothing here: change a note by running `trades note` again.
  - **Beside each fill** in the *every call* table on Candles and Calls (Sentiment and Note columns).
  - **As a reason** in Decision Reasons, which shows how often you acted on each sentiment and whether those
    trades paid.
  A marker appears for a call as soon as you have noted it, so there is no need to also annotate the chart by
  hand; the `BUY` / `SELL` chart annotations remain for marking a moment you have not traded on.

### Part 6 - dashboards

```bash
cd grafana && docker compose up -d    # http://localhost:3000
```

See [grafana/README.md](grafana/README.md).

### Part 7 - CAN SLIM

```bash
export SCREENER_SESSION_ID=...      # the 'sessionid' cookie from a signed-in screener.in browser session

# screen with the configured query, then read every candidate's daily chart
java $JOPTS -cp $CP com.stockanalyzer.CanSlimMain scan
java $JOPTS -cp $CP com.stockanalyzer.CanSlimMain scan --query "Return on equity > 20 AND YOY Quarterly profit growth > 30"
java $JOPTS -cp $CP com.stockanalyzer.CanSlimMain scan --all          # list every scored stock, not just signals

# skip the query: score only holdings and the configured symbols (the nightly job does this when no
# session is set, with their fundamentals read from public screener.in company pages)
java $JOPTS -cp $CP com.stockanalyzer.CanSlimMain scan --no-screener
```

screener.in runs the fundamental filter - **C**urrent and **A**nnual earnings
growth, and **I**nstitutional buying - and its results are priced with a year
of daily candles. Locally, each stock is scored for **N** (near its 52-week
high), **S** (volume on up days over down days), **L** (an RS percentile) and
**M** (the market's direction, from NIFTYBEES). Then:

- **Buy** - a close through a base's pivot on 1.4x average volume, within 5% of it.
- **Watch** - within 5% under the pivot of a sound base: about to rally, or not.
- **Sell** - 8% under the buy point, a heavy-volume close below the 50-day
  average, or a climax run: rallies that have ended or are ending. These are
  checked for open positions and the configured universe too.

**The whole market.** `CanSlimMain scan --market` (or `canslim.market.enabled=true` for every
nightly scan) also prices and ranks every listed NSE and BSE equity - about 2,800 on the main
boards, from Groww's instrument list - so the RS rating is a percentile of the market. Illiquid
and stale listings are priced but left out of the ranking; company pages on screener.in are read
only for the stocks that rank high enough to be a buy, and only companies confirmed above ₹1,000 Cr
of market cap (`canslim.market.min.market.cap.cr`) are selected - so the list fills in over the first
nights, strongest first. `--limit 50` tries it on a spread of 50
symbols first, and `--top N` sets how many of the strongest by RS the report lists. See
[docs/CANSLIM.md](docs/CANSLIM.md).

The **CAN SLIM** dashboard shows the market, both lists, and each stock's daily
chart with its pivot. The rules, the thresholds and what is approximated are in
[docs/CANSLIM.md](docs/CANSLIM.md).

### The original ML growth report

The pre-existing `Main` entry point is untouched and still works as before:

```bash
java -jar target/stock-analyzer.jar
```

## Scheduling

Four `launchd` jobs cover routine use — the daemon at 08:58 on weekdays, a
nightly batch at 18:30, a weekly refresh of the statistical prior on Saturday,
and a monthly close. Scripts and plists are in [ops/](ops/), and
[ops/README.md](ops/README.md) is the runbook: what runs when, why the evening
order is what it is, what to do when a job fails, and why you should never run a
backfill while the daemon is live.

A missed run costs nothing: the planner works out what is missing by set
difference, so the next run catches up.

## Configuration

Edit `src/main/resources/application.properties`, or override any key via an
environment variable (dots become underscores, uppercased — e.g.
`groww.api.key` -> `GROWW_API_KEY`). Prefer env vars for secrets.

| Key | Purpose |
|---|---|
| `groww.auth.mode` | `checksum` (API key + API secret, the default) or `totp` (TOTP token + TOTP secret) |
| `groww.api.key`, `groww.api.secret` | Groww's "API key" and "API secret"; required when `groww.auth.mode=checksum` |
| `groww.totp.token`, `groww.totp.secret` | Groww's "TOTP token" and "TOTP secret" (the Base32 seed); required when `groww.auth.mode=totp` |
| `groww.exchange`, `groww.segment` | Defaults to `NSE` / `CASH` |
| `groww.candle.interval.minutes` | `1440` = daily candles |
| `groww.lookback.days` | How far back to fetch (default 30) |
| `ml.service.url` | Your ML service's prediction endpoint |
| `app.symbols.file` | Classpath file listing symbols, one per line (default `symbols.txt`, pre-populated with 50 NSE large-caps — edit freely) |
| `app.fetch.concurrency` | Parallel fetch/analyze threads (default 5, keep modest — see rate limits below) |

Added by parts 1-6, grouped by prefix (see `application.properties` for the full
annotated list):

| Prefix | Controls |
|---|---|
| `intraday.*` | Candle interval, how many windows to find, `HIGH_LOW` vs `CLOSE_CLOSE`, minimum hold and minimum gain |
| `db.*` | Where the SQLite file lives |
| `ingest.*`, `backfill.*` | Rate limits, retries, and how many days one request may span |
| `hotwindow.*`, `model.*` | The time-of-day prior, and the optional model service |
| `alerts.*` | Sell targets and tick size, how stale a target may be, sinks, holiday file; three `alerts.hotwindow.*` settings tune the baseline entry signal |
| `monitor.*` | Poll interval, session hours, timezone |
| `trades.*`, `charges.*`, `report.fy.start.month` | Trade import (including `trades.live.import.interval.minutes`, how often the daemon pulls the day's fills during the session), the charge schedule, and the financial year |

## How it fits together

Package layout follows the existing SOLID conventions — every collaborator sits
behind an interface, and all concrete wiring happens in one composition root
(`AppContext`), so no service knows it is talking to Groww specifically or to
SQLite specifically.

- **`model`** — plain records. No behavior, no dependencies.
- **`auth`** — two token flows behind one `GrowwAuthenticator` interface:
  `ChecksumGrowwAuthenticator` (API key + secret) and `TotpGrowwAuthenticator`
  (API key + a current authenticator code, the pairing the Python SDK exposes as
  `get_access_token(api_key=..., totp=...)`). `GrowwAuthenticators.create` picks
  one from `groww.auth.mode`; caching and refresh are shared, so a third flow is
  one method. TOTP codes are RFC 6238, generated in-process from the seed with
  no third-party dependency.
- **`client`** — candle fetching.
  `RateLimitedCandleDataClient`, `RetryingCandleDataClient` and
  `ChunkedCandleDataClient` are decorators, so limits, backoff and request
  splitting are each enforced in exactly one place.
- **`intraday`** — `TopKNonOverlappingDetector` (the top-3 windows),
  `TradingCalendar`, `BackfillPlanner`, `DailyIngestionService`,
  `RecomputeService`. The detector is pure: same candles in, same windows out.
- **`store`** — repository interfaces with SQLite implementations behind them,
  plus `SchemaMigrator`. Moving to DuckDB or Postgres later means adding one
  package, not editing services.
- **`features`**, **`signal`** — event detection, the feature contract, the
  time-of-day prior, and the optional model client.
- **`alert`**, **`live`** — the alert engine and sinks, and the market-hours
  monitor they share a tick with.
- **`trade`** — FIFO lot matching, the charge model, period aggregation, reason
  attribution and capture analysis.
- **`report`** — console, CSV and dashboard-facing output.

Three entry points (`DailyAnalysisMain`, `MarketDayDaemon`, `TradeJournalMain`)
plus the original `Main`.

## Design decisions worth knowing

- **"Highest gain" is defined, not assumed.** A gain window is an entry and a
  later exit, scored on the entry candle's low against the exit candle's high by
  default, and the top 3 are forced not to overlap — so you get three genuinely
  distinct opportunities, not three shifted views of one move. Switch to
  `CLOSE_CLOSE` for the conservative reading.
- **Every result carries a detector version.** Changing the rules writes a
  parallel, comparable series instead of silently rewriting history.
- **Ingestion is a set difference, not a cursor.** The nightly run and a
  multi-month backfill are the same code path, and a symbol that failed three
  days ago is simply still missing next time.
- **Provisional data never reaches the canonical tables.** The still-forming
  candle is shown in the live view and excluded from anything analysed or
  stored; the session is reconciled against the authoritative tape at the close.
- **Charges are modelled, never ignored.** On intraday equity they are a large
  fraction of a small move, so every P&L figure is shown gross and net.
- **The statistical baseline is the default.** The monitor works with
  no ML at all; a trained model has to beat the prior on a held-out month before
  `model.enabled` is worth turning on.

## Testing

```bash
mvn test
```

Fakes over mocks, matching the existing style. Notable cases: the detector
against hand-built sessions with known answers; ingesting the same day twice
leaving exactly three windows; the backfill planner against gaps, weekends and
holidays; FIFO matching through partial fills, short-then-cover and simultaneous
MIS/CNC positions; sell-target alerts (average buy price, limit rounding, restart dedup); and every Grafana
dashboard query executed against a real migrated database.

## Important caveats

- **CAN SLIM works without a screener.in session, with less reach.** The
  fundamentals for your holdings and configured symbols are read from screener.in's
  public company pages, so they are scored with or without a login. Only the query
  - finding candidates outside those symbols - needs one.
- **CAN SLIM reads the query through your browser session.** Custom queries
  need a signed-in account, and screener.in signs in through Google in a
  browser - a flow a batch job cannot complete, and should not hold the
  credentials for. So the scan sends the `sessionid` cookie you copy from a
  signed-in browser; when it expires, the scan exits with status 3 and says so.
  The query is one request for the first page of results: screener.in's
  robots.txt asks automated clients not to use `page`, `limit` or `sort`, so none
  is sent, and a query matching more than a page says so and should be tightened.
  The result only changes when companies report, so running it once a week is
  plenty. A query that names a ratio screener.in does not know fails with
  screener.in's own error message.

- The historical candle endpoint used (`GET /v1/historical/candle/range`) is
  marked **deprecated** in Groww's docs in favor of a newer "Backtesting" data
  endpoint; it was used here because it is the one with a fully documented
  request/response shape. If Groww removes it, update `GrowwCandleDataClient` to
  target the replacement endpoint.
- The checksum auth algorithm (`SHA-256(apiSecret + epochTimestamp)`, hex
  encoded) was reconstructed from Groww's published cURL docs. If token requests
  start failing with 4xx, re-check the exact concatenation order/encoding
  against the latest docs at https://groww.in/trade-api/docs/curl.
- **The broker's order list only serves the current day.** There is no date
  parameter and no page of older orders, so `trades import --broker` can only
  ever fetch today's fills — asking for a wider range logs a warning and returns
  what today has. That is why the journal is built up by importing every
  evening, and why anything older has to come from a contract note through
  `trades import --csv`.
- Fetching fills takes two calls: `GET /v1/order/list` for the order book, then
  `GET /v1/order/trades/{growwOrderId}` per filled order. `groww_trade_id` is the
  natural key, so re-importing the same day is a no-op.
- **`nse-holidays-2026.txt` ships incomplete.** It has the fixed-date national
  holidays only. Festival holidays move every year and must be copied from the
  NSE's published circular, or the daemon will treat those days as trading days.
  History self-corrects (a day with no data for any symbol is recorded as
  non-trading), but the calendar looks forward and cannot.
- **The charge rates are as configured, not as billed.** Verify them once against
  a real contract note; after that `charges.prefer.broker.actuals` means broker
  figures win wherever they exist.
- **Rate limits.** One shared token bucket enforces per-second, per-minute and
  per-day ceilings across every thread in the process, and every entry point
  goes through it (`RateLimiterCoverageTest` fails the build if one stops
  doing so). Defaults are deliberately conservative — 5/sec, 120/min, 5000/day —
  because being throttled mid-session costs far more than a backfill taking a
  few extra minutes. The figures published for general non-trading endpoints are
  higher, but historical-data endpoints are usually capped lower, so confirm
  against the current docs before raising them.
  A 429 pauses *every* worker for the server's `Retry-After`, not just the
  thread that was refused. The daily counter is per-process and in-memory: a
  guardrail against one runaway job, not an account-wide ledger. And separate
  JVMs get separate buckets, which is why a backfill should not run while the
  daemon is live.
- This repo still does not include a model. Part 2 works without one; if you
  want one, `docs/MODEL-CONTRACT.md` is the wire format and
  `src/test/resources/feature-parity.json` is how you check your features match.
