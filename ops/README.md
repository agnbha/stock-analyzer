# Daily operating schedule

Four scheduled jobs cover routine use. Everything is idempotent, so a missed run
costs nothing — the next one catches up.

All times are IST. If the machine is not on IST, either set its timezone or
shift the hours in the plists to match a 09:15–15:30 session.

| When | Job | What it runs | Why then |
|---|---|---|---|
| **08:58** Mon–Fri | `market-day.sh` | `MarketDayDaemon` | Plans the day's alerts before the 09:00 pre-open alert is due. Exits on its own after the close — this is a daily launch, not a service to keep alive. |
| 09:15–15:30 | — | the daemon ticks every 150s | Nothing manual. Poll → re-run the top-3 detector on the partial day → detect events → score → fire alerts → redraw. |
| ~15:32 | — | the daemon reconciles and exits | Re-fetches the authoritative tape and writes the canonical rows through the same Part 1 path the nightly job uses. |
| **18:30** Mon–Fri | `evening.sh` | ingest → evaluate → import fills → capture → day P&L | Fills have settled by evening. |
| **Sat 10:00** | `weekly.sh` | hot-windows, 14-day gap backfill, week P&L | The prior has to be current before Monday's open, because the daemon reads it when planning alerts. |
| **1st, 10:00** | `monthly.sh` | month P&L, capture, reasons, statement export | The books for the month just closed. |

## Why the evening order is what it is

It is dependency-driven, not arbitrary:

1. **`daily`** writes `trading_day` and the top-3 windows.
2. **`evaluate`** scores yesterday's predictions against the now-complete tape,
   so it needs step 1 to have finished.
3. **`trades import`** brings in fills, matches lots, and attributes reasons —
   reasons come from the events and alerts the daemon recorded during the day.
   This step is why the evening job matters most: the broker's order list only
   serves the current day, so a session missed here cannot be fetched later.
4. **`capture`** compares step 3 against step 1. It has to be last, or it has
   nothing to compare against.
5. **`pnl`** prints the day.

If the daemon already reconciled the session at 15:32, step 1 finds nothing
missing and does nothing. If the daemon never ran, step 1 is what saves the day.
Either way you end up with the same rows, which is the point of routing both
paths through one ingestion service.

One knock-on worth knowing: the live view's DAY% comes from the *previous*
session's stored close. Skip the evening job and tomorrow's monitor shows `-`
in that column until the next successful ingest.

## Install

```bash
mvn clean package

# Credentials live outside the repo so nothing secret can be committed.
cat > ~/.stock-analyzer.env <<'ENV'
GROWW_API_KEY=...
GROWW_AUTH_MODE=checksum        # or totp
GROWW_API_SECRET=...            # checksum mode
# GROWW_TOTP_SECRET=...         # totp mode
DB_URL=jdbc:sqlite:/Users/you/projects/stock-analyzer/data/stock-analyzer.db
ENV
chmod 600 ~/.stock-analyzer.env

cp ops/launchd/*.plist ~/Library/LaunchAgents/
for job in marketday evening weekly monthly; do
  launchctl unload ~/Library/LaunchAgents/com.stockanalyzer.$job.plist 2>/dev/null
  launchctl load  ~/Library/LaunchAgents/com.stockanalyzer.$job.plist
done
launchctl list | grep stockanalyzer
```

The plists carry absolute paths, so regenerate or edit them if the repo moves.

## Running a job by hand

Every script takes the same arguments as the underlying command and is safe to
re-run:

```bash
ops/bin/evening.sh                 # today
ops/bin/evening.sh 2026-08-27      # a specific session
ops/bin/weekly.sh
ops/bin/market-day.sh --dry-run    # alerts to console only
```

## When something fails

- **A step fails, the batch continues.** A broken trade import must not cost you
  the night's analysis. The script logs which steps failed and exits non-zero.
- **Some symbols fail during ingest → nothing to do.** The planner works out
  what is missing by set difference, so a symbol that failed tonight is simply
  still missing tomorrow and gets picked up then.
- **Every symbol fails → the job exits non-zero.** That is not a quiet market
  day; it means credentials, connectivity or an API entitlement is broken, and
  it is deliberately loud so a scheduled run cannot fail silently.
- **Check the daemon is alive:** `java -cp target/stock-analyzer.jar
  com.stockanalyzer.MarketDayDaemon status` prints the last heartbeat and warns
  when it is more than ten minutes old.
- Logs are in `logs/`: `market-day-YYYY-MM-DD.log` plus per-job stdout/stderr.

## Do not run a backfill during market hours

The rate limiter is shared *within* a process, not across processes. The daemon
and a second JVM running `backfill` each get their own token bucket, so together
they can exceed the provider's ceilings and get everything throttled — including
the live monitor. They would also contend for the SQLite write lock.

A 429 now pauses every worker in that process for as long as the server's
`Retry-After` says, so one throttled request no longer leaves the other four
threads hammering. That helps within a process; it does nothing across two.

Run backfills in the evening, or on a weekend. The Saturday job already covers a
rolling 14-day window for anything that slipped through.

## One-off jobs, not scheduled

```bash
# Seed history the first time
java -cp target/stock-analyzer.jar com.stockanalyzer.DailyAnalysisMain \
     backfill --from 2026-01-01 --to 2026-08-30

# After changing the detector rules - writes a parallel, comparable series
java -cp target/stock-analyzer.jar com.stockanalyzer.DailyAnalysisMain \
     recompute --from 2026-01-01

# After changing the charge schedule
java -cp target/stock-analyzer.jar com.stockanalyzer.TradeJournalMain pnl --rebuild

# Record what the account actually holds, so the equity curve is a real claim
java -cp target/stock-analyzer.jar com.stockanalyzer.TradeJournalMain \
     trades balance --cash 250000 --invested 50000
```

**Every December:** add next year's NSE holidays to
`src/main/resources/nse-holidays-2026.txt` (or a new file, pointed at by
`alerts.holidays.file`) and rebuild. The calendar warns at startup when its last
date is under 30 days away. History self-corrects — a day with no data for any
symbol is recorded as non-trading — but scheduled alerts look forward and
cannot.
