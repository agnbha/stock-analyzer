# Stock Analyzer

A personal intraday analysis, alerting and trade-journal system for NSE
equities, built on the [Groww Trading API](https://groww.in/trade-api/docs).
Six parts, sharing one local database and one market-hours process:

| Part | What it does | Runs |
|---|---|---|
| 1 | Daily intraday analysis: the top 3 gain windows per symbol per session, appended to months of history | nightly, batch |
| 2 | A time-of-day prior, and a hook for a trained model | offline, plus online scoring |
| 3 | Alerts for the session lifecycle and for historically strong moments | during market hours |
| 4 | Live monitoring every 2-3 minutes, projecting new candles as they arrive | during market hours |
| 5 | Trade journal and gain/loss statements per day, week, month and financial year | on demand |
| 6 | Grafana dashboards over the same database | on demand |

The full design, including why each decision was made, is in
[docs/DESIGN.md](docs/DESIGN.md).

> This produces statistics, signals, notifications and record-keeping. It places
> no orders and makes no buy/sell recommendation. Part 5's P&L is for your own
> review, not a tax filing - your broker's contract note remains authoritative.

## Authentication

Two flows, selected with `groww.auth.mode`:

```properties
# API key + secret, signed as SHA-256(secret + timestamp)
groww.auth.mode=checksum
groww.api.key=...
groww.api.secret=...

# API key + a current six-digit authenticator code
groww.auth.mode=totp
groww.api.key=...
groww.totp.secret=JBSWY3DPEHPK3PXP     # the Base32 seed, not a code
```

`groww.totp.secret` is the seed shown when two-factor authentication was enabled
— the string an authenticator app scans — not the six digits it displays. Codes
are minted per request and never cached, so **the machine's clock has to be
roughly right**: on a server, keep NTP running, or tokens will be rejected with
what looks like a bad-credential error. Prefer environment variables
(`GROWW_API_KEY`, `GROWW_API_SECRET`, `GROWW_TOTP_SECRET`) over the properties
file for all three.

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

# yesterday's session for every configured symbol
java -cp $CP com.stockanalyzer.DailyAnalysisMain daily
java -cp $CP com.stockanalyzer.DailyAnalysisMain daily --date 2026-08-27

# seed the previous months (same code path, just a wider range)
java -cp $CP com.stockanalyzer.DailyAnalysisMain backfill --from 2026-06-01 --to 2026-08-27

# re-run a changed detector over stored candles, no API calls
java -cp $CP com.stockanalyzer.DailyAnalysisMain recompute --from 2026-06-01

# read the accumulated history back
java -cp $CP com.stockanalyzer.DailyAnalysisMain report --symbol RELIANCE --months 6
java -cp $CP com.stockanalyzer.DailyAnalysisMain export --from 2026-06-01 --out opportunities.csv
```

### Part 2 - the time-of-day prior

```bash
java -cp $CP com.stockanalyzer.DailyAnalysisMain hot-windows --lookback 60
java -cp $CP com.stockanalyzer.DailyAnalysisMain evaluate --date 2026-08-27
```

### Parts 3 and 4 - alerts and live monitoring

```bash
java -cp $CP com.stockanalyzer.MarketDayDaemon            # run for today
java -cp $CP com.stockanalyzer.MarketDayDaemon --dry-run  # console only, no notifications
java -cp $CP com.stockanalyzer.MarketDayDaemon status     # is it alive?
```

### Part 5 - the trade journal

```bash
java -cp $CP com.stockanalyzer.TradeJournalMain trades import --broker --from 2026-08-01 --to 2026-08-28
java -cp $CP com.stockanalyzer.TradeJournalMain trades import --csv contract-notes.csv
java -cp $CP com.stockanalyzer.TradeJournalMain trades add --symbol RELIANCE --side BUY \
     --qty 10 --price 1418.20 --at 2026-08-28T09:47 --product MIS --reason "VWAP reclaim"
java -cp $CP com.stockanalyzer.TradeJournalMain trades balance                    # read it from the broker
java -cp $CP com.stockanalyzer.TradeJournalMain trades balance --cash 250000        # fallback: enter it by hand
java -cp $CP com.stockanalyzer.TradeJournalMain trades reasons --month 2026-08

java -cp $CP com.stockanalyzer.TradeJournalMain pnl --period day   --date  2026-08-28
java -cp $CP com.stockanalyzer.TradeJournalMain pnl --period week  --of    2026-08-28
java -cp $CP com.stockanalyzer.TradeJournalMain pnl --period month --month 2026-08
java -cp $CP com.stockanalyzer.TradeJournalMain pnl --period fy    --year  2026-27
java -cp $CP com.stockanalyzer.TradeJournalMain pnl --rebuild
java -cp $CP com.stockanalyzer.TradeJournalMain statement --from 2026-04-01 --out statement.csv
java -cp $CP com.stockanalyzer.TradeJournalMain capture --month 2026-08
```

### Part 6 - dashboards

```bash
cd grafana && docker compose up -d    # http://localhost:3000
```

See [grafana/README.md](grafana/README.md).

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
| `groww.api.key` | Groww API key (needed by both auth flows) |
| `groww.auth.mode` | `checksum` (API key + secret, the default) or `totp` (API key + authenticator code) |
| `groww.api.secret` | Required when `groww.auth.mode=checksum` |
| `groww.totp.secret` | Required when `groww.auth.mode=totp` — the Base32 seed from two-factor setup |
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
| `alerts.*` | Sinks, lead time, evidence thresholds, cooldowns and caps, holiday file |
| `monitor.*` | Poll interval, session hours, timezone |
| `trades.*`, `charges.*`, `report.fy.start.month` | Trade import, the charge schedule, and the financial year |

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
- **The statistical baseline is the default.** Alerts and the monitor work with
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
MIS/CNC positions; alert cooldowns and restart dedup; and every Grafana
dashboard query executed against a real migrated database.

## Important caveats

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
  NSE's published circular, or the daemon will fire session alerts on days the
  market is shut. History self-corrects (a day with no data for any symbol is
  recorded as non-trading), but scheduled alerts look forward and cannot.
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
