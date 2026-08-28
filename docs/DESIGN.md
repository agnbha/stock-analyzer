# Stock Analyzer — Project Design

A personal stock analysis and alerting system, built as an incremental
extension of the existing `com.stockanalyzer` codebase (Java 25, Maven, Groww
Trading API, SOLID layering with a composition root in `Main`).

Five parts, designed together so they share one data model and one process:

| Part | What it does | Runs |
|---|---|---|
| 1 | Daily intraday analysis: top 3 gain windows per symbol per session, appended to months of history in a local DB | nightly, batch |
| 2 | ML model inferring which times and events are worth acting on | offline training, online scoring |
| 3 | Alerts: session start/end, and moments when today's clock reaches a historically significant timestamp | during market hours |
| 4 | Live monitoring every 2–3 minutes, projecting new candles and showing change as it happens | during market hours |
| 5 | Trade journal and gain/loss statements per day, week and month | after the fact, on demand |

Parts 3 and 4 are one long-running process (`MarketDayDaemon`): the monitor's
poll tick is exactly what evaluates alert conditions, so they are designed as
one runtime with two output surfaces.

> Scope note: this system produces statistics, signals, notifications and
> record-keeping. It places no orders and makes no buy/sell recommendation —
> every decision stays with you. Part 5's P&L figures are for your own review;
> they are not a tax filing, and the contract note from your broker remains the
> authoritative document.

---

## 0. System shape

```
                      ┌──────────────────────────────────────┐
   Groww API ────────►│ client/ (auth, rate-limit, chunk,     │
                      │          retry, provider-agnostic)    │
                      └───────────────┬──────────────────────┘
                                      │ StockCandleSeries
                ┌─────────────────────┼─────────────────────┐
                ▼                     ▼                     ▼
        Part 1 nightly          Part 4 live poll      Part 2 training
        DailyIngestionService   MarketDayDaemon        (offline, Python)
                │                     │                     │
                │  top-3 windows      │ provisional candles │ model artifacts
                ▼                     ▼                     ▼
        ┌───────────────────────────────────────────────────────┐
        │  store/  — SQLite behind repository interfaces         │
        │  instrument · candle · trading_day · gain_opportunity  │
        │  hot_window · event · prediction · alert_log · runs    │
        │  trade · realized_lot · pnl_period · attribution       │
        └───────────────────────────────────────────────────────┘
                │            │             │             ▲
                ▼            ▼             ▼             │ fills
          report/ CLI   Part 3 alerts  Part 2 scoring    │
          Part 5 P&L    (console/…)    (REST seam)   broker trade book
                │                                    / CSV / manual entry
                └──► capture ratio: what Part 1 says was available
                     vs what Part 5 says you actually took
```

Design rules carried over from the current codebase: every collaborator sits
behind an interface, all concrete wiring happens only in a composition root,
and no service class knows it is talking to Groww specifically or to SQLite
specifically.

---

# Part 1 — Regular stock analysis

## 1.1 Scope

Once per trading day, for each configured symbol:

1. Fetch the **previous session's intraday candles** (1-minute by default).
2. Identify the **top 3 timestamps where the highest gain could have been
   made**, each with its **gain percentage**.
3. **Append** to the accumulated history of previous months, idempotently.
4. Persist in a **local database**, with a documented path to a bigger store.
5. Report today's top-3 per symbol, and query across the accumulated months.

## 1.2 Defining "highest gain"

"Top 3 timestamps where highest gains could be made" needs a precise meaning
before it can be computed:

> A **gain opportunity** is an ordered pair of intraday timestamps
> `(entry, exit)` with `entry < exit`, scored by
> `gainPct = (exitPrice − entryPrice) / entryPrice × 100`.
> The reported "timestamp" is the **entry** — the moment at which acting would
> have produced the gain.

Two knobs, both configurable:

| Knob | Default | Meaning |
|---|---|---|
| `priceBasis` | `HIGH_LOW` | entry at the entry candle's **low**, exit at the exit candle's **high** — the best fill actually present in the tape. `CLOSE_CLOSE` is the conservative alternative. |
| `overlap` | non-overlapping | the 3 windows may not share candles, so you get 3 genuinely distinct opportunities rather than three shifted views of one move. |

Plus `minHoldCandles` (default 1 — exit strictly after entry) and `minGainPct`
(default 0.0 — a day that never rises yields fewer than 3 rows rather than
fabricated ones).

## 1.3 Algorithm

Best single interval in a candle range is one Kadane-style pass: sweep
left→right tracking the lowest `low` so far and its index; at each candle `j`
evaluate `(high[j] − minLow) / minLow`. O(n).

Top-K non-overlapping intervals on top of that:

```
segments = maxHeap keyed by bestGainOf(segment)
push [0, n-1]
repeat K times:
    seg = segments.pop()
    (i, j, gain) = bestIntervalIn(seg)
    if gain < minGainPct: stop
    emit (i, j, gain)
    push [seg.lo, i-1]      # candles strictly before the entry
    push [j+1, seg.hi]      # candles strictly after the exit
```

`K = 3` and `n ≈ 375` for one NSE session of 1-minute candles (09:15–15:30), so
cost is irrelevant — correctness and reproducibility are what matter. Every
emitted opportunity carries a **detector version**
(e.g. `topk-nonoverlap/highlow/v1`) so changing the rules later creates a new
comparable series instead of silently rewriting history.

## 1.4 Storage

**SQLite** (`org.xerial:sqlite-jdbc`), one file under `./data/`: zero ops,
transactional, trivially backed up, queryable from any tool. Volume is modest —
50 symbols × ~250 sessions/year × 3 rows ≈ 37.5k opportunity rows/year; raw
1-minute candles add ~4.7M rows/year, still comfortable.

Because access goes through repository interfaces, moving to DuckDB (better
columnar analytics) or Postgres later means adding one package, not editing
services.

### Schema v1 — Part 1 core

```sql
CREATE TABLE instrument (
  id       INTEGER PRIMARY KEY,
  symbol   TEXT NOT NULL, exchange TEXT NOT NULL, segment TEXT NOT NULL,
  name     TEXT,
  UNIQUE (symbol, exchange, segment)
);

-- one row per instrument per session: the incremental unit of work
CREATE TABLE trading_day (
  id               INTEGER PRIMARY KEY,
  instrument_id    INTEGER NOT NULL REFERENCES instrument(id),
  session_date     TEXT NOT NULL,           -- 'YYYY-MM-DD', Asia/Kolkata
  interval_minutes INTEGER NOT NULL,
  open REAL, high REAL, low REAL, close REAL, volume INTEGER,
  day_change_pct   REAL,                    -- close vs prior session close
  candle_count     INTEGER NOT NULL,
  first_candle_ts  INTEGER, last_candle_ts INTEGER,   -- epoch seconds, UTC
  source           TEXT NOT NULL,           -- 'groww'
  ingested_at      INTEGER NOT NULL,
  UNIQUE (instrument_id, session_date, interval_minutes)
);

-- the Part 1 deliverable
CREATE TABLE gain_opportunity (
  id               INTEGER PRIMARY KEY,
  trading_day_id   INTEGER NOT NULL REFERENCES trading_day(id) ON DELETE CASCADE,
  detector_version TEXT NOT NULL,
  rank             INTEGER NOT NULL,        -- 1 = biggest gain
  entry_ts         INTEGER NOT NULL, exit_ts INTEGER NOT NULL,   -- epoch, UTC
  entry_price      REAL NOT NULL, exit_price REAL NOT NULL,
  gain_pct         REAL NOT NULL,
  duration_minutes INTEGER NOT NULL,
  UNIQUE (trading_day_id, detector_version, rank)
);
CREATE INDEX ix_opp_gain ON gain_opportunity (gain_pct DESC);

-- raw tape, so detectors can be re-run without re-fetching
CREATE TABLE candle (
  instrument_id INTEGER NOT NULL REFERENCES instrument(id),
  interval_minutes INTEGER NOT NULL,
  ts_epoch INTEGER NOT NULL,
  open REAL, high REAL, low REAL, close REAL, volume INTEGER,
  PRIMARY KEY (instrument_id, interval_minutes, ts_epoch)
) WITHOUT ROWID;

CREATE TABLE non_trading_day (
  session_date TEXT PRIMARY KEY,
  reason TEXT,                              -- 'weekend' | 'holiday-published' | 'holiday-inferred'
  observed_at INTEGER NOT NULL
);

CREATE TABLE ingestion_run (
  id INTEGER PRIMARY KEY,
  started_at INTEGER NOT NULL, finished_at INTEGER,
  session_date TEXT, mode TEXT,             -- 'daily' | 'backfill' | 'live'
  requested INTEGER, succeeded INTEGER, failed INTEGER,
  status TEXT                               -- RUNNING | OK | PARTIAL | FAILED
);

CREATE TABLE ingestion_failure (
  run_id INTEGER NOT NULL REFERENCES ingestion_run(id),
  symbol TEXT NOT NULL, session_date TEXT NOT NULL,
  error_type TEXT, message TEXT, attempts INTEGER
);

CREATE TABLE schema_migration (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL);
```

Points that matter in practice:

- **Timestamps** are epoch seconds UTC (exactly what Groww returns);
  `session_date` is the derived Asia/Kolkata calendar date and is the key
  humans and the backfill planner use. Never mix the two.
- **Prices** as `REAL` is fine for analysis; if exact arithmetic is ever needed
  the migration is to integer paise — hence prices live in one place.
- **Idempotency**: `trading_day` upserts on its natural key; opportunities for a
  `(trading_day, detector_version)` are deleted-then-inserted in the same
  transaction. Re-running any day converges to one answer.
- **Recompute without refetch**: `detector_version` in the key means a rules
  change writes a parallel series to compare against before dropping the old.

## 1.5 Incremental ingestion

`BackfillPlanner` computes work as a set difference, not a date cursor:

```
wanted  = tradingDaysBetween(from, to)      # weekdays minus non_trading_day
have    = SELECT session_date FROM trading_day WHERE instrument_id = ?
missing = wanted − have
```

Daily runs and multi-month backfills therefore share one code path — a daily
run is just `from = to = lastTradingDay`. It also self-heals: a symbol that
failed three days ago is still missing, and is picked up on the next run with
no retry bookkeeping.

Reliability details, each necessary at 50 symbols × 60 days:

- **Rate limiting** — Groww allows 20 req/sec and 500 req/min on non-trading
  endpoints. `RateLimitedCandleDataClient` is a decorator holding one token
  bucket (default 15/sec, 400/min), so the limit is enforced in a single place
  no matter how many worker threads exist.
- **Range chunking** — intraday endpoints cap the span per request.
  `CandleRangeChunker` splits a window into provider-legal chunks
  (`backfill.max.days.per.request`, default 5) and stitches the results.
- **Retries** — exponential backoff with jitter on HTTP 429/5xx, bounded by
  `ingest.max.retries` (default 4). Other errors fail that one symbol-day into
  `ingestion_failure` without aborting the batch — the same isolate-per-symbol
  behaviour `StockGrowthAnalysisService` already implements.
- **Concurrency vs SQLite** — fetch and detect on the existing fixed thread
  pool; writes funnel through a single writer thread, one transaction per
  symbol-day, with `PRAGMA journal_mode=WAL` and a busy timeout.
- **Holiday learning** — a weekday returning zero candles for *every* symbol is
  recorded as `holiday-inferred`; empty for only some symbols is a per-symbol
  failure, not a holiday.

---

# Part 2 — ML model for entry/exit times and events

## 2.1 What is being learned

Two complementary things, deliberately kept separate:

**(a) The time-of-day prior — statistics, not ML.** Aggregate months of Part 1
results into per-bucket frequency: how often does a 5-minute bucket of the
session contain a top-3 entry, and what gain follows? This is cheap, fully
explainable, directly answers "which times", and — importantly — is the
**baseline any model must beat**. It is also what Part 3's recurring-timestamp
alerts consume.

```sql
CREATE TABLE hot_window (
  id INTEGER PRIMARY KEY,
  instrument_id INTEGER REFERENCES instrument(id),   -- NULL = market-wide
  bucket_start_minute INTEGER NOT NULL,   -- minutes since 09:15 IST
  bucket_minutes INTEGER NOT NULL,        -- 5
  lookback_days INTEGER NOT NULL,         -- window the stats were computed over
  hits INTEGER NOT NULL, sessions INTEGER NOT NULL,
  hit_rate REAL NOT NULL,
  hit_rate_lcb REAL NOT NULL,             -- Wilson lower bound — rank by THIS
  mean_gain_pct REAL, median_gain_pct REAL,
  computed_at INTEGER NOT NULL,
  UNIQUE (instrument_id, bucket_start_minute, bucket_minutes, lookback_days)
);
```

Ranking by the Wilson lower confidence bound rather than raw hit rate is the
detail that stops a 2-of-2 bucket outranking a 40-of-100 bucket. Without it the
whole feature is noise.

**(b) The supervised model — "which events".** Per-candle classification over
the stored tape.

*Labels* — triple-barrier over a horizon `H` (default 30 min): from candle `t`,
whichever comes first — upper barrier `+u%` → `ENTRY`, lower barrier `−d%` →
`AVOID`, horizon expiry → `NEUTRAL`. Barriers scale with recent ATR so the
labels mean the same thing in calm and volatile regimes.

*Features* (all computable from the local DB, no extra API calls):

| Group | Features |
|---|---|
| Clock | minutes since open, sin/cos of session progress, day of week |
| Momentum | returns over 1/5/15/30/60 min, sign runs |
| Volatility | rolling stdev, ATR, candle body/wick ratios |
| Volume | volume z-score **vs the same time-of-day mean** (intraday volume is strongly U-shaped; a raw z-score just relabels "it is 09:20") |
| Position | distance from VWAP, from opening-range high/low, from prior-day high/low/close, position within today's range |
| Gap | overnight gap vs prior close |
| Regime | NIFTY index return same-bar, market breadth across the universe |
| Events | the boolean event flags below |

*Events* are rule-derived, human-legible, and stored in their own table so an
alert can say **why** it fired: opening-range breakout, VWAP reclaim/loss,
volume spike (`z > 3`), gap-and-go, prior-day-high break, reversal after N
consecutive down candles, first-hour high break.

```sql
CREATE TABLE event (
  id INTEGER PRIMARY KEY,
  instrument_id INTEGER NOT NULL REFERENCES instrument(id),
  ts_epoch INTEGER NOT NULL, session_date TEXT NOT NULL,
  event_type TEXT NOT NULL, strength REAL,
  detector_version TEXT NOT NULL,
  UNIQUE (instrument_id, ts_epoch, event_type, detector_version)
);
```

Keeping events as first-class rows means they serve three purposes at once:
model features, alert reasons, and a standalone "what happened today" report.

## 2.2 Validation — the part that decides whether this is real

Intraday ML fails in exactly two ways, and both are prevented structurally:

- **Leakage.** Labels look forward `H` minutes, so a random train/test split
  leaks the future. Use **purged walk-forward CV with an embargo**: train on
  months 1..k, validate on k+1, and drop every sample within `H` of the
  boundary. No shuffling, ever.
- **Overfitting to a small personal dataset.** 50 symbols × 250 sessions × 375
  candles is ~4.7M rows but far fewer *independent* events. Guardrails: a fixed
  held-out final month never touched during development; report performance
  against the Part 2(a) time-of-day baseline, not against 0; and require a
  model to beat that baseline on the held-out month before it is allowed to
  drive an alert.

*Metrics*: precision@k (you act on few signals, so the top of the ranking is
all that matters), mean realized forward return of fired signals **net of an
assumed cost** (brokerage + slippage, configurable, default 0.05% round trip),
hit rate, and a sanity comparison against "buy at open, sell at close".

## 2.3 Serving

Reuse the seam that already exists. The repo has `GrowthPatternAnalyzer` +
`RestGrowthPatternAnalyzer` + `ml.service.url`; Part 2 adds a sibling interface
rather than complicating that one:

```java
public interface IntradaySignalModel {
    List<SignalPrediction> score(SignalRequest request);   // batch of candles+features
    String modelVersion();
}
```

- `RestIntradaySignalModel` → Python service (`POST /predict/intraday`).
  Python is where gradient-boosted trees (LightGBM/XGBoost — the right family
  for tabular market features, not deep learning at this data size) and the
  training pipeline live. Java stays a thin, testable client.
- `HotWindowSignalModel` → the pure-Java statistical baseline, no service
  required. **This is the default**, so Parts 3 and 4 work end to end before any
  model exists, and degrade to it whenever the ML service is down.

Every prediction is stored so the model can be scored honestly after the fact:

```sql
CREATE TABLE model_version (
  id INTEGER PRIMARY KEY, name TEXT NOT NULL, trained_at INTEGER,
  train_from TEXT, train_to TEXT, metrics_json TEXT, active INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE prediction (
  id INTEGER PRIMARY KEY,
  model_version_id INTEGER NOT NULL REFERENCES model_version(id),
  instrument_id INTEGER NOT NULL, ts_epoch INTEGER NOT NULL,
  session_date TEXT NOT NULL,
  signal TEXT NOT NULL,                  -- ENTRY | EXIT | NEUTRAL
  probability REAL NOT NULL, horizon_minutes INTEGER NOT NULL,
  realized_return_pct REAL,              -- filled in by the evaluator job next day
  UNIQUE (model_version_id, instrument_id, ts_epoch)
);
```

A nightly `evaluate` job fills `realized_return_pct` from the now-complete tape,
which makes model drift visible without any extra instrumentation.

*Realistic expectation, stated once:* recurring intraday timestamps are far less
stable than a first look at the histogram suggests, and much of what a model
finds on a few months of one exchange will be noise. The design's answer is the
baseline comparison and the held-out month — treat a model that cannot beat the
time-of-day prior as a null result, which is a normal outcome here.

---

# Part 3 — Alerts

## 3.1 Alert types

**(a) Session lifecycle** — pre-open (09:00 IST), open (09:15), mid-session
(12:00), closing window (15:15), close (15:30). Weekdays only, skipping
holidays.

This needs a **forward-looking** calendar: the `holiday-inferred` mechanism from
Part 1 only works backwards. So `TradingCalendar` gains a `HolidaySource` —
a checked-in, hand-editable `nse-holidays-2026.txt` (the NSE publishes the list
annually) — with the inferred table still used for historical questions. A
startup warning fires if the holiday file's last date is under 30 days away.

**(b) Recurring-timestamp alerts** — the direct answer to "moments where similar
timestamps are reached in the day". At session start the daemon loads the
`hot_window` rows for each symbol and schedules a fire a configurable lead time
(default 2 min) before each qualifying bucket:

```
09:45  RELIANCE approaching a historically strong window (09:47–09:52)
       top-3 entry on 14 of the last 60 sessions · median +0.9% · hit-rate LCB 0.14
       today: +0.3% on the day, volume 1.8× normal for this time
```

Qualification thresholds (`alerts.hotwindow.min-sessions`, default 30;
`alerts.hotwindow.min-lcb`, default 0.10) keep thin statistics from generating
noise.

**(c) Live condition alerts** — an `event` fired now, or a Part 2 model
probability crossing `alerts.model.min-probability` (default 0.65). Suppressed
automatically whenever the active model has not beaten the baseline.

## 3.2 Delivery and control

```java
public interface AlertSink { void publish(Alert alert); }
```

Implementations: `ConsoleAlertSink`, `MacNotificationSink` (osascript /
terminal-notifier), `FileAlertSink` (JSONL, for later analysis), and optionally
`TelegramAlertSink` / `EmailAlertSink`. Same pluggable pattern as the existing
`AnalysisReporter`. A `CompositeAlertSink` fans out.

Alert fatigue is the actual failure mode of this feature, so control is part of
the design, not a later fix:

- **Deterministic day plan** — at session start the daemon materialises the
  day's scheduled alerts into `alert_schedule`, so you can inspect what *will*
  fire before it does.
- **Cooldown** per `(symbol, rule)`, default 15 min.
- **Daily cap** per symbol, default 3, and a global cap, default 30.
- **Dedup on restart** — every fired alert is written to `alert_log` with an
  idempotency key `(session_date, symbol, rule, bucket)`; a daemon restart
  re-reads it and never re-fires.
- **Severity** (`INFO` / `NOTABLE` / `URGENT`) routed per sink, so lifecycle
  alerts stay in the console while only `URGENT` reaches a desktop notification.

```sql
CREATE TABLE alert_schedule (
  id INTEGER PRIMARY KEY, session_date TEXT NOT NULL,
  fire_at_epoch INTEGER NOT NULL, instrument_id INTEGER, rule TEXT NOT NULL,
  payload_json TEXT, status TEXT NOT NULL      -- PENDING | FIRED | SKIPPED
);

CREATE TABLE alert_log (
  id INTEGER PRIMARY KEY, session_date TEXT NOT NULL,
  fired_at_epoch INTEGER NOT NULL, instrument_id INTEGER,
  rule TEXT NOT NULL, severity TEXT NOT NULL, message TEXT NOT NULL,
  idempotency_key TEXT NOT NULL UNIQUE
);
```

---

# Part 4 — Live monitoring

## 4.1 The loop

`MarketDayDaemon` runs from pre-open to post-close and ticks every
`monitor.poll.interval.seconds` (default **150 s** = 2.5 min):

```
tick:
  1. fetch candles since last watermark, per symbol   (rate-limited, chunked)
  2. append to in-memory LiveSessionState per symbol
  3. run the SAME Part 1 detector over the partial day  -> "top 3 so far"
  4. run event detectors over new candles                -> event rows
  5. score with the active IntradaySignalModel           -> prediction rows
  6. evaluate alert rules (Part 3)                       -> AlertSink
  7. push updated state to every LiveView
```

Request budget: 50 symbols per 150 s is 0.33 req/s — two orders of magnitude
under the 20 req/s limit, so the interval can safely be tightened to 60 s later
if wanted. The same `RateLimitedCandleDataClient` is shared with the nightly
job, so the two can never collectively exceed the limit.

## 4.2 Provisional vs final data — the rule that keeps the DB trustworthy

The candle currently forming is incomplete: its high, low and close all still
change. Treating it as final would poison both the history and the model.

- In-memory `LiveSessionState` holds completed candles plus **one provisional
  candle**, explicitly flagged.
- Live candles are written only to a `live_candle` staging table (or kept purely
  in memory — configurable), never directly into `candle`.
- At session close, `SessionReconciler` re-fetches the full authoritative day
  and writes the canonical `trading_day` + `candle` + `gain_opportunity` rows
  through the **exact same Part 1 code path**. Any divergence between the live
  view and the final tape is logged — that log is the early warning that the
  provider's live feed is lagging or the entitlement has changed.

The repo's README already documents a case where a Groww entitlement was
missing and fundamentals had to be derived from candles. The monitor assumes the
same can happen to live data: on entitlement or feed failure it degrades to
delayed candles, **labels the display as delayed with an age in seconds**, and
keeps running. A stale number shown as live is worse than no number.

## 4.3 Projection and display

"Projecting new candles" is served by two clearly distinguished things:

1. **Extension** — the in-progress candle and the running "top 3 so far",
   derived only from real ticks. Solid in the display.
2. **Projection** — a short-horizon extrapolation (last close plus the active
   model's expected forward return over `H`, with a confidence band). Rendered
   dimmed/dashed, labelled `proj`, and **never persisted as fact** — only as a
   `prediction` row that gets scored the next day.

```java
public interface LiveView { void render(LiveSnapshot snapshot); }
```

- `TerminalLiveView` — default; ANSI redraw, one row per symbol, sorted by
  today's change, sparkline of the last 30 candles.
- `DashboardLiveView` — optional local HTTP page on `localhost:8080`, polling a
  JSON snapshot endpoint. Same `LiveSnapshot`, different renderer.

```
NSE · 2026-08-28 11:42:15 IST · live (3s) · next poll 11:44:45 · 50 symbols

SYMBOL     LTP      DAY%   VOL×  TOP-3 SO FAR (entry→exit, gain)     PROJ 30m  SIGNAL
RELIANCE  1418.20  +1.05   1.8   09:47→10:22 +2.14  11:05→11:20 +0.6   +0.4%   ENTRY .71
TCS       3402.55  -0.22   0.9   11:02→11:38 +1.73                     -0.1%   —
...
▲ 11:40 alert  RELIANCE approaching strong window 11:45–11:50 (17/60, med +0.8%)
```

## 4.4 Resilience

- Network blip → keep last known state, show staleness age, exponential backoff,
  never blank the screen.
- `monitor_heartbeat` row updated every tick; a `daemon status` subcommand reads
  it so a silently-dead daemon is detectable.
- Clean shutdown on SIGTERM: flush staging, run reconciliation if the session
  has ended, close the DB.
- Single-instance lock file, so a second daemon cannot double-fire alerts.

---

# Part 5 — Trade journal and gain/loss statements

## 5.1 Getting trades in

Everything downstream depends on a faithful record of fills, so capture is
designed with three sources behind one interface:

```java
public interface ExecutionDataClient {
    List<Trade> fetchTrades(LocalDate from, LocalDate to);
}
```

| Source | Implementation | Use |
|---|---|---|
| Broker trade book | `GrowwExecutionClient` (order/trade endpoints, same auth as candles) | primary — authoritative, includes actual charges |
| CSV / contract note | `CsvTradeImporter` with a configurable column mapping | history predating this tool, or another broker |
| Manual | `trades add` CLI subcommand | one-offs and corrections |

Idempotency mirrors Part 1: `broker_trade_id` is unique, so re-importing an
overlapping date range is a no-op. Manual and CSV trades get a deterministic
synthetic id derived from `(symbol, side, qty, price, executed_ts)`, so the same
row imported twice collapses to one.

## 5.2 Accounting rules

Three decisions determine whether the numbers are correct:

**FIFO lot matching.** A sell consumes the oldest open buy lots first, producing
one `realized_lot` row per (buy lot, sell) pairing. FIFO is what Indian equity
tax treatment expects, and per-lot rows — rather than a running average — are
what make holding period, per-trade P&L, and win rate meaningful.

**Intraday and delivery are accounted separately.** `MIS`/intraday and
`CNC`/delivery carry different charges and different tax character
(speculative vs capital gains), so `product` is part of the lot-matching key: an
intraday buy never nets against a delivery position.

**Charges are modelled, not ignored.** On Indian equity intraday, brokerage plus
STT, exchange transaction charges, SEBI fees, stamp duty and GST are a large
fraction of a small move — a gross-P&L report would be actively misleading.

```java
public interface ChargeModel { Charges compute(Trade trade); }
```

`GrowwChargeModel` holds the rates in config, but **actual broker charges always
win** when the trade book supplies them; the model covers manual and CSV trades.
Every report shows gross and net side by side, plus charges as a percentage of
turnover — the number that quietly decides whether frequent intraday trading is
viable at all.

Unrealized P&L on open positions marks to the last stored close from Part 1's
`trading_day`, so the journal needs no extra API calls.

## 5.3 Schema (v2 additions)

```sql
CREATE TABLE trade (
  id INTEGER PRIMARY KEY,
  instrument_id INTEGER NOT NULL REFERENCES instrument(id),
  broker_trade_id TEXT NOT NULL UNIQUE,     -- real, or deterministic synthetic
  order_id TEXT,
  session_date TEXT NOT NULL, executed_ts INTEGER NOT NULL,
  side TEXT NOT NULL,                       -- BUY | SELL
  quantity INTEGER NOT NULL, price REAL NOT NULL,
  product TEXT NOT NULL,                    -- MIS | CNC
  charges_total REAL, charges_json TEXT,    -- broker actuals when available
  charges_source TEXT NOT NULL,             -- 'broker' | 'modelled'
  source TEXT NOT NULL,                     -- 'broker' | 'csv' | 'manual'
  imported_at INTEGER NOT NULL,
  notes TEXT
);
CREATE INDEX ix_trade_session ON trade (session_date, instrument_id);

CREATE TABLE realized_lot (
  id INTEGER PRIMARY KEY,
  instrument_id INTEGER NOT NULL REFERENCES instrument(id),
  buy_trade_id INTEGER NOT NULL REFERENCES trade(id),
  sell_trade_id INTEGER NOT NULL REFERENCES trade(id),
  product TEXT NOT NULL,
  quantity INTEGER NOT NULL,
  buy_price REAL NOT NULL, sell_price REAL NOT NULL,
  opened_ts INTEGER NOT NULL, closed_ts INTEGER NOT NULL,
  holding_minutes INTEGER NOT NULL,
  gross_pnl REAL NOT NULL,
  charges_allocated REAL NOT NULL,          -- pro-rata by quantity
  net_pnl REAL NOT NULL,
  return_pct REAL NOT NULL,
  UNIQUE (buy_trade_id, sell_trade_id)
);

CREATE TABLE open_position (
  instrument_id INTEGER NOT NULL REFERENCES instrument(id),
  product TEXT NOT NULL,
  quantity INTEGER NOT NULL, avg_cost REAL NOT NULL,
  opened_ts INTEGER NOT NULL, last_updated INTEGER NOT NULL,
  PRIMARY KEY (instrument_id, product)
);

-- materialised rollups; always recomputable from trade + realized_lot
CREATE TABLE pnl_period (
  id INTEGER PRIMARY KEY,
  period_type TEXT NOT NULL,                -- DAY | WEEK | MONTH | FY
  period_start TEXT NOT NULL, period_end TEXT NOT NULL,
  instrument_id INTEGER,                    -- NULL = all symbols
  product TEXT,                             -- NULL = all products
  trades INTEGER NOT NULL, closed_lots INTEGER NOT NULL,
  wins INTEGER NOT NULL, losses INTEGER NOT NULL, win_rate REAL,
  gross_pnl REAL NOT NULL, charges REAL NOT NULL, net_pnl REAL NOT NULL,
  turnover REAL NOT NULL, charges_pct_turnover REAL,
  avg_win REAL, avg_loss REAL, profit_factor REAL,
  best_lot_pnl REAL, worst_lot_pnl REAL,
  unrealized_end REAL,
  computed_at INTEGER NOT NULL,
  UNIQUE (period_type, period_start, instrument_id, product)
);

-- the loop back to Parts 1-3
CREATE TABLE trade_attribution (
  trade_id INTEGER PRIMARY KEY REFERENCES trade(id),
  gain_opportunity_id INTEGER REFERENCES gain_opportunity(id),
  alert_log_id INTEGER REFERENCES alert_log(id),
  entry_lag_minutes INTEGER,                -- your entry vs the ideal entry
  capture_pct REAL                          -- your gain / the gain available
);
```

Period boundaries: `DAY` = a session date; `WEEK` = ISO week starting Monday;
`MONTH` = calendar month; `FY` = 1 April – 31 March, because that is the period
that actually matters in India. `pnl_period` is a cache — every figure is
derivable from `trade` + `realized_lot`, and `pnl --rebuild` regenerates it, so
a bug in the rollup can never corrupt the underlying record.

## 5.4 Reports

```
P&L · MONTH 2026-08 · all symbols · net of charges

Realized        +18,420.55        Turnover      24,80,000
Charges          −2,145.30  (0.09% of turnover)
Net realized    +16,275.25
Unrealized       +3,110.00  (2 open positions)

Closed lots  47   Wins 29 (61.7%)   Avg win  +1,102   Profit factor  1.84
                  Losses 18         Avg loss   −685

By week          NET        LOTS   WIN%
2026-08-03    +4,220.10      12    66.7
2026-08-10    −1,880.45      11    36.4
2026-08-17    +7,455.00      13    76.9
2026-08-24    +6,480.60      11    63.6

Top symbols    RELIANCE +8,120 (12 lots) · TCS +4,010 (7) · INFY −2,240 (6)
```

The same aggregation serves `--period day` and `--period week` unchanged; only
the boundary function differs. Output surfaces reuse the existing `report/`
seam — `ConsolePnlReporter`, `CsvStatementExporter` (one row per realized lot,
spreadsheet-ready), and an optional `HtmlStatementReporter` with an equity
curve. Each is an implementation of one interface, so adding a format never
touches the accounting.

## 5.5 Capture analysis — where Part 5 pays for Parts 1–4

Because Part 1 already knows the best three windows of every session and Part 3
knows which alerts fired, the journal can answer questions no broker statement
can:

- **Capture ratio** — your realized gain on a symbol-day against the top-3 gain
  that was available. Aggregated monthly, this measures improvement far better
  than P&L, which is dominated by market direction.
- **Entry lag** — minutes between the ideal entry and yours, trended over
  months. A shrinking lag is skill; a constant one is a fixable process problem.
- **Alert follow-through** — of the alerts that fired, which you acted on, and
  whether acting beat not acting. This is also the honest way to decide whether
  Part 2's model is worth leaving switched on.

```bash
... TradeJournalMain capture --month 2026-08
```

These land in `trade_attribution`, computed by a nightly job after Part 1's
ingestion completes for the session, matching each trade to the nearest
opportunity window for that symbol-day.

---

# Cross-cutting

## Package layout

```
com.stockanalyzer
├── Main                          # existing ML growth report — untouched
├── DailyAnalysisMain             # NEW: daily | backfill | recompute | report | export
├── MarketDayDaemon               # NEW: Parts 3+4 long-running process
├── TradeJournalMain              # NEW: trades | pnl | statement | capture
├── auth/       (existing, unchanged)
├── client/     + RateLimitedCandleDataClient, CandleRangeChunker, RetryingCandleDataClient
├── config/     AppConfig + intraday.* / db.* / ingest.* / model.* / alerts.* / monitor.*
├── model/      + TradingSession, GainOpportunity, DailyGainSummary,
│                 MarketEvent, SignalPrediction, LiveSnapshot
├── intraday/   GainOpportunityDetector (+TopKNonOverlappingDetector), PriceBasis,
│               TradingCalendar (+HolidaySource), BackfillPlanner, DailyIngestionService
├── features/   FeatureExtractor, EventDetector (+ per-event rules), VwapCalculator
├── signal/     IntradaySignalModel, HotWindowSignalModel, RestIntradaySignalModel,
│               HotWindowCalculator, PredictionEvaluator
├── alert/      AlertRule, AlertEngine, AlertScheduler, AlertSink (+Console/Mac/File/…)
├── live/       MarketDayDaemon, LiveSessionState, SessionReconciler,
│               LiveView (+Terminal/Dashboard)
├── trade/      ExecutionDataClient (+Groww/Csv/manual), FifoLotMatcher,
│               ChargeModel (+GrowwChargeModel), PnlCalculator,
│               PeriodAggregator, CaptureAnalyzer
├── store/      repository interfaces + Database + SchemaMigrator + jdbc/Sqlite*
└── report/     + DailyOpportunityReporter, HistoryReporter, CsvExporter,
                ConsolePnlReporter, CsvStatementExporter, HtmlStatementReporter

ml-service/     NEW sibling Python project: training pipeline, feature parity
                tests, FastAPI serving /predict/intraday
```

The one genuine cross-language risk is **feature parity**: features computed in
Java at serving time must match those computed in Python at training time. The
mitigation is a shared fixture — a checked-in candle series plus expected
feature vectors as JSON, asserted by tests on both sides. Skipping this is how
these systems silently rot.

## Entry points

```bash
# Part 1 — nightly
java -cp target/stock-analyzer.jar com.stockanalyzer.DailyAnalysisMain daily
... DailyAnalysisMain daily --date 2026-08-27
... DailyAnalysisMain backfill --from 2026-01-01 --to 2026-08-27
... DailyAnalysisMain recompute --from 2026-01-01 --detector highlow-v2
... DailyAnalysisMain report --symbol RELIANCE --months 6
... DailyAnalysisMain export --from 2026-01-01 --out opportunities.csv

# Part 2
... DailyAnalysisMain hot-windows --lookback 60      # recompute the statistical prior
... DailyAnalysisMain evaluate --date 2026-08-27     # score yesterday's predictions
(cd ml-service && python train.py --from 2026-01-01 --to 2026-07-31)

# Parts 3+4 — market hours
java -cp target/stock-analyzer.jar com.stockanalyzer.MarketDayDaemon
... MarketDayDaemon --dry-run       # alerts to console only, no notifications
... MarketDayDaemon status

# Part 5
java -cp target/stock-analyzer.jar com.stockanalyzer.TradeJournalMain \
      trades import --broker --from 2026-08-01 --to 2026-08-28
... TradeJournalMain trades import --csv contract-notes.csv
... TradeJournalMain trades add --symbol RELIANCE --side BUY --qty 10 \
                     --price 1418.20 --at 2026-08-28T09:47 --product MIS
... TradeJournalMain pnl --period day   --date  2026-08-28
... TradeJournalMain pnl --period week  --of    2026-08-28
... TradeJournalMain pnl --period month --month 2026-08
... TradeJournalMain pnl --period fy    --year  2026-27
... TradeJournalMain pnl --rebuild --from 2026-04-01
... TradeJournalMain statement --from 2026-04-01 --to 2026-08-28 --out statement.csv
... TradeJournalMain capture --month 2026-08
```

Scheduling on macOS: one `launchd` plist starting `MarketDayDaemon` at 09:00 IST
on weekdays (it exits itself after close and reconciliation), and one running
`DailyAnalysisMain daily` at 18:30. A missed nightly run costs nothing — the
set-difference planner catches up next time.

## New configuration keys

```properties
# Part 1
intraday.candle.interval.minutes=1
intraday.top.n=3
intraday.price.basis=HIGH_LOW            # or CLOSE_CLOSE
intraday.min.hold.candles=1
intraday.min.gain.pct=0.0
intraday.store.raw.candles=true

db.url=jdbc:sqlite:data/stock-analyzer.db
db.busy.timeout.ms=5000

ingest.rate.limit.per.second=15
ingest.rate.limit.per.minute=400
ingest.max.retries=4
backfill.max.days.per.request=5

# Part 2
model.enabled=false                      # HotWindowSignalModel until a model earns its place
model.service.url=http://localhost:8001/predict/intraday
model.horizon.minutes=30
model.cost.round.trip.pct=0.05
hotwindow.bucket.minutes=5
hotwindow.lookback.days=60

# Part 3
alerts.enabled=true
alerts.sinks=console,macos
alerts.lead.time.minutes=2
alerts.hotwindow.min-sessions=30
alerts.hotwindow.min-lcb=0.10
alerts.model.min-probability=0.65
alerts.cooldown.minutes=15
alerts.max.per.symbol.per.day=3
alerts.max.per.day=30
alerts.holidays.file=nse-holidays-2026.txt

# Part 4
monitor.poll.interval.seconds=150
monitor.session.open=09:15
monitor.session.close=15:30
monitor.timezone=Asia/Kolkata
monitor.view=terminal                    # terminal | dashboard | both
monitor.dashboard.port=8080
monitor.persist.live.candles=false

# Part 5
trades.source=broker                     # broker | csv | manual
trades.csv.date.format=yyyy-MM-dd HH:mm:ss
trades.lot.matching=FIFO
trades.default.product=MIS
charges.prefer.broker.actuals=true
charges.brokerage.intraday.pct=0.05
charges.brokerage.intraday.max=20
charges.brokerage.delivery.pct=0.0
charges.stt.intraday.sell.pct=0.025
charges.stt.delivery.pct=0.1
charges.exchange.txn.pct=0.00297
charges.sebi.pct=0.0001
charges.stamp.duty.buy.pct=0.003
charges.gst.pct=18
report.fy.start.month=4
```

The `charges.*` rates are the schedule as configured, not a source of truth —
verify them against a real contract note once, then let
`charges.prefer.broker.actuals` keep them out of the way.

All follow the existing `AppConfig` convention: properties file, overridden by
`UPPER_SNAKE_CASE` env vars.

## Testing strategy

| Area | Approach |
|---|---|
| `TopKNonOverlappingDetector` | hand-built series with known answers: single spike, two equal spikes, monotonic decline (0 rows), flat day, overlap rejection, `minHoldCandles` boundary |
| Repositories | temp-file SQLite per test; migration from empty; ingesting a day twice leaves exactly 3 opportunity rows |
| `BackfillPlanner` | fake repository with gaps, weekends, known holidays |
| Rate limiter / chunker | fake clock; assert request spacing and chunk boundaries |
| Feature parity | shared JSON fixture asserted identically in Java and Python |
| Model validation | test that the CV splitter never leaks across the purge boundary — a failing splitter is invisible in production but invalidates every metric |
| Alert engine | fake clock; assert cooldown, daily cap, and no re-fire after simulated restart |
| Live daemon | scripted fake `CandleDataClient` replaying a recorded session at speed; assert provisional candles never reach `candle`, and that reconciliation reproduces the batch result exactly |
| FIFO matcher | partial fills, several buys against one sell, short-then-cover, MIS and CNC held simultaneously in the same symbol |
| Charge model | a real contract note as a fixture; assert modelled charges land within a stated tolerance of the broker's actuals |
| Period aggregation | days sum to the week, weeks to the month; `pnl --rebuild` reproduces the cached rows exactly |
| Trade import | re-importing an overlapping date range adds zero rows |
| End-to-end | recorded Groww JSON fixture → DB → report, no network |

Fakes over mocks, matching the existing `StockGrowthAnalysisServiceTest` style.

## Delivery plan

| Phase | Deliverable | Part | Depends on |
|---|---|---|---|
| 1 | `store`: `Database`, `SchemaMigrator` v1, repositories + tests | 1 | — |
| 2 | Detector + `PriceBasis` + unit tests (pure logic, no I/O) | 1 | — |
| 3 | `DailyIngestionService` + `daily` for one date | 1 | 1, 2 |
| 4 | Rate limiter, chunker, retries, `TradingCalendar`, `BackfillPlanner`, `backfill` | 1 | 3 |
| 5 | `report` / `export` / `recompute` + nightly launchd job | 1 | 3 |
| 6 | `HotWindowCalculator` + `hot_window` + `hot-windows` command | 2a | 5 |
| 7 | `EventDetector` + `event` table + event report | 2b | 5 |
| 8 | `MarketDayDaemon` skeleton: poll loop, `LiveSessionState`, `TerminalLiveView`, reconciliation | 4 | 3 |
| 9 | `AlertEngine` + sinks + lifecycle and hot-window alerts | 3 | 6, 8 |
| 10 | `ml-service`: labelling, features, walk-forward CV, training | 2b | 6, 7 |
| 11 | `RestIntradaySignalModel`, `prediction` storage, `evaluate` job, model-driven alerts | 2b | 9, 10 |
| 12 | Projection rendering + optional dashboard view | 4 | 8, 11 |
| 13 | `trade` schema, importers (broker/CSV/manual), `trades` commands | 5 | 1 |
| 14 | `FifoLotMatcher` + `ChargeModel` + `realized_lot` + unit tests | 5 | 13 |
| 15 | `PeriodAggregator` + `pnl` day/week/month/FY + `statement` export | 5 | 14 |
| 16 | `CaptureAnalyzer` + `trade_attribution` + `capture` report | 5 | 5, 9, 15 |

Phases 1 and 2 are independent. Phase 2 is pure functions and is where the
design risk actually sits, so it is the one worth building first if only one
thing gets built.

Phases 13–15 depend only on phase 1, so Part 5 can be built in parallel with
Parts 2–4 — and it is the fastest of the five to reach something useful, since
it needs no live infrastructure at all.

Everything through phase 9 works with **no ML at all** — the statistical
baseline drives real alerts. That ordering is deliberate: it keeps the system
useful long before phase 10, and gives phase 11 a genuine benchmark to beat.

## Assumptions and open questions

Decisions taken, each reversible by a config key:

1. Gain measured entry-low → exit-high, non-overlapping, reported by entry
   timestamp (`intraday.price.basis`).
2. 1-minute candles. 5-minute cuts storage and request count ~5× if Groww's
   1-minute history depth proves too short for multi-month backfill.
3. SQLite, single local file, raw candles stored.
4. Same universe as the existing `symbols.txt` (50 NSE large-caps).
5. Alerts default to console + macOS notification; `model.enabled=false` until a
   model beats the baseline on a held-out month.
6. Live polling at 150 s, in-memory only, reconciled to the canonical tables at
   close.
7. Trades imported from the broker trade book, FIFO matched, MIS and CNC kept
   separate, reported net of actual-or-modelled charges.

Worth settling before phase 3, and before phase 10:

- **How many months should the initial backfill cover?** This decides whether
  1-minute candles are viable, since intraday history depth is the main
  provider-side constraint — and it also sets the ceiling on what Part 2 can
  learn. (Under ~6 months, the statistical prior is the honest stopping point.)
- **Should a day with no positive move record its best negative windows, or
  nothing?** Current default: nothing.
- **Where should alerts land when you are away from the terminal** — desktop
  only, or a phone channel (Telegram is the least-friction option)?
- **Does your Groww API key expose the trade book, and how far back?** That
  decides whether phase 13 is an API importer or a CSV importer over contract
  notes. If the history window is short, exporting it once now is worth doing
  before it ages out.
- **Do you trade anything outside this API** — another broker, or F&O? Part 5
  works per-instrument either way, but F&O needs contract metadata and a
  different charge schedule; better known before phase 14 than after.
- **Does the Groww key carry the Live Data entitlement?** If not, Part 4 runs on
  delayed candles, which changes what "live" means for alerts — worth confirming
  before phase 8 rather than discovering it at runtime.
