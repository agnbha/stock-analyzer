# CAN SLIM

William O'Neil's method, from *How to Make Money in Stocks*: buy companies with
fast-growing earnings, as they break out of a sound base, in a rising market;
cut losses short and sell into exhaustion. This document is what
`CanSlimMain scan` checks, where each number comes from, and what is only
approximated.

## How a scan runs

1. **Screen.** The `canslim.screener.query` query runs on screener.in. It is the
   fundamental filter, because quarterly and annual results live there. Every
   ratio it mentions comes back as a column, which is how the letters below get
   their numbers. This needs a signed-in session (see the last section); without
   one the step is skipped, the report says so, and the scan carries on at step 2.
   It is one request for the first page of results: screener.in's robots.txt asks
   automated clients not to use its `page`, `limit` or `sort` parameters, so none
   is sent. A query that matches more than a page says so in the report - tighten
   it (a higher market cap floor, stricter thresholds) until it fits on one.
2. **Widen.** Open positions and the configured `symbols.txt` universe join the
   screened companies. They are checked for sell signals and widen the field the
   RS rating is ranked against. Their own C, A and I come from their public
   screener.in company pages, so they are scored like any other stock - and a
   held or universe stock whose fundamentals pass both C and A is a buy
   candidate too, not just a sell check. See *When a page is read again* below.
3. **Price.** About a year of daily candles per symbol, plus the market proxy
   (`NIFTYBEES`). The daily interval allows years per request, so this is one
   request per symbol, through the same rate limiter as everything else. A
   session still in progress is left out: half a day's volume would read as a
   collapse.
4. **Score and decide.** Each letter, then a signal (below).
5. **Store.** Daily bars go to `daily_bar`, and the run and every score go to
   `canslim_run` / `canslim_score`, so the dashboard can show today's lists and
   each stock's history of readings.

## The whole market

By default a scan covers the screener's results, your holdings and `symbols.txt`. With
`canslim.market.enabled=true` (or `CanSlimMain scan --market` for one run) it also covers
**every listed NSE and BSE equity**, and the RS rating becomes what O'Neil intends: a
percentile of the *market*, not of a hand-picked field.

1. **List.** Groww's public instrument list (`instrument.csv`, kept in `data/` and refreshed
   daily) is filtered to equity shares - the file also carries bonds, ETFs, funds and F&O
   contracts, told apart by the security-type digits of the ISIN. A company on both exchanges
   is taken once, from NSE; BSE adds only the companies NSE does not list, priced on BSE and
   looked up on screener.in by BSE scrip code. About 2,800 symbols on the main boards.
2. **Price.** One daily-candle request each, through the shared rate limiter (allow 20-45
   minutes), analysed as it arrives so a year of bars is not held for every symbol. Daily bars
   are stored like any other scan's.
3. **Rank.** Priced is not ranked. A symbol joins the RS field only with `min.sessions` of
   history, a last close of at least `min.price`, an average daily value traded of at least
   `min.turnover` over 50 sessions, and a bar no older than `max.stale.days`. Without that the
   thousands of listings that barely trade would sit in the field, and everything real would look
   strong for having beaten them. The report counts what was left out and why.
4. **Re-evaluate.** With the full field ranked, every symbol is scored again with its RS rating.
5. **Fundamentals, for the leaders only.** screener.in is asked for a company's figures only once
   it reaches `fundamentals.min.rs` (the L bar, 80): below it the stock cannot be a buy whatever its
   earnings say, so the page load would buy nothing. That is a few hundred pages the first time and
   a handful a night after, refreshed by the calendar (see *When a page is read again*).
   screener.in admits a burst of about seven quick reads and then roughly one every ten seconds - a
   faster pace gets HTTP 429 - so pages are read 12 seconds apart, with a pause and one retry on a
   429, and at most `canslim.fundamentals.max.pages.per.run` (60, about 12 minutes) in a scan. The
   first scans therefore fill in over several nights: holdings and the universe first, then the
   leaders, strongest RS first. Until a leader's page has been read its C, A and I show '?'.
6. **Report.** The usual signals, then the strongest stocks by RS rating, strongest first (`--top
   N`, 50 by default). The CAN SLIM dashboard's last table is ordered the same way.

Differences from the rest of the scan: a market listing is **never given a sell signal** - nobody
holds it, so a breakout that fell back is only a stock - and it is a buy candidate only if its own
C and A pass. Market-wide scores are thousands of rows a night, so only the newest
`canslim.market.keep.runs` scans (30) keep theirs; holdings and screened companies are never pruned.

The SME platforms (NSE SM/ST, BSE X/XT/M/MT) are left out by default: they add ~3,000 symbols that
trade in lots with narrow circuits and next to no volume, and the liquidity test would rank almost
none of them. `canslim.market.include.sme=true` prices them anyway.

## The letters

A letter is **passed**, **failed**, or **unknown** when its data was not
available. Unknown is not the same as failed: an ETF has no earnings, and a
company page may lack a figure (a new listing without five quarters of results has
no year-on-year growth).

| Letter | Rule | Default | From |
|---|---|---|---|
| **C** Current earnings | latest quarter's profit vs the same quarter last year (four columns back); sales too, so it is not one-off cost cutting. A loss-making base has no growth rate and is unknown | profit ≥ 25%, sales ≥ 20% | screener.in query, or company page |
| **A** Annual earnings | three-year profit CAGR, and return on equity | ≥ 25%, ROE ≥ 17% | screener.in query, or company page |
| **N** New highs | close within this far of the 52-week high | ≤ 15% below | daily bars |
| **S** Supply and demand | volume on up days over volume on down days, last 50 sessions | ≥ 1.0 | daily bars |
| **L** Leader | RS rating: percentile of a weighted 12-month return (40% on the latest quarter, 20% on each earlier one) | ≥ 80 | daily bars |
| **I** Institutional sponsorship | FII + DII holding change last quarter | > 0 | screener.in query, or company page |
| **M** Market direction | the market proxy's state, below | confirmed uptrend | daily bars |

All thresholds are `canslim.*` properties.

## The market (M)

From the proxy's daily bars:

- **Correction:** below a 50-day average that is lower than ten sessions ago,
  or **6+ distribution days** in the last 25 sessions.
- **Uptrend under pressure:** below the 50-day average, below the 200-day, or
  **4-5 distribution days**.
- **Confirmed uptrend:** otherwise.

A distribution day is a decline of at least 0.2% on volume above the previous
session's: institutions selling into the market.

## Bases and pivots

A base is a consolidation after an advance. The analyzer finds the **pivot** the
way a chart reader would: the highest high that nothing since has exceeded, at
least five weeks (25 sessions) old, whose correction since is no deeper than
35%. If the 63 sessions before the pivot are available, the stock must have
risen at least 20% into it; a recent listing without that history still
qualifies. This covers flat bases, cups and saucers by their shared shape
without classifying patterns. The pivot used is the base's high, which is
slightly stricter than O'Neil's handle-high pivot for a cup with handle.

## Signals

Sell signals are checked first and win: protecting capital comes before a new
entry.

| Signal | When | Applies to |
|---|---|---|
| `SELL_STOP_LOSS` | close ≥ 8% below the buy point: the holding's average cost, else the latest breakout's pivot | anything with a buy point |
| `SELL_TREND_BREAK` | a close below the 50-day average in the last five sessions, on ≥ 1.4x average volume, still below it | anything that had a rally: a breakout in the last 60 sessions, a holding, or a 25%+ advance off a six-month low |
| `SELL_CLIMAX` | ≥ 20% above the buy point (or the 50-day average) and either up 25% in 15 sessions, or the biggest one-day gain since the breakout on the heaviest volume, in the last three sessions | the same |
| `BUY_BREAKOUT` | today closed through the pivot from at or below it, on ≥ 1.4x the 50-day average volume, and is ≤ 5% above it | screened candidates whose C, A and L are not failing |
| `BUY_ZONE` | the same breakout in the last five sessions, still 0-5% above the pivot | the same |
| `WATCH_SETUP` | within 5% under the pivot of a valid base (or above it without breakout volume): **about to rally** | the same |
| `EXTENDED` | broke out, now more than 5% above the pivot | the same |
| `AVOID_MARKET` | would be a buy or a watch, but the market is in correction | the same |

In an uptrend under pressure, buy signals stand but say so.

## What is approximated

- **The RS field.** IBD ranks every listed stock; here the field is whatever the
  scan priced. Screened candidates already have strong earnings, so an 80 here
  is a harder bar than IBD's 80.
- **The market's volume** comes from an index ETF, not the index. Distribution
  days are not retired after a 5% rally, which errs towards caution.
- **Follow-through days** (how O'Neil confirms a new uptrend after a correction)
  are not modelled; the 50-day average and distribution count stand in for them.
- **N** is read only as "near new highs". New products, management or industry
  conditions are not in the data.
- **Earnings acceleration**, a lower float, and industry-group leadership are
  not checked.

## When a page is read again

The figures move when a company reports a quarter, so a page is read again by the
calendar and not on a timer. Each cached copy records the newest quarter its
results table and its shareholding table showed (`Sep 2026`).

- A copy showing the **latest quarter that has ended** is left alone, however old,
  until the next quarter ends. Nothing on the page can change before then.
- From **8 to 60 days after a quarter ends**, a company still showing the older
  quarter is looked at again every `canslim.fundamentals.season.check.days` (2).
  Eight days is before anyone reports; sixty is the filing deadline for the
  year-end quarter (45 for the others, 21 for the shareholding pattern). A company
  stops being checked the day it shows the new quarter.
- **Past the deadline**, a company still missing the quarter is late or suspended,
  and drops to `canslim.fundamentals.cache.days` (7).
- A page with **no figures** (an ETF) is retried at that same slow pace.
- A copy **more than a quarter behind** is read at once, and so is one stored
  before quarters were recorded (once, to learn which quarter it holds).

The annual figures (three-year profit growth, ROE) change when the year-end
quarter's results arrive with the annual accounts, so the same rule covers them.
The scan's report counts how many symbols are still showing an earlier quarter
than the one that has just ended - the ones the next few nights will be checking.

## The screener.in session

Only the **query** needs a session; the company pages the fundamentals fall back
on are public. Without one, the scan still runs and the report says "No
screener.in session"; what is missing is new candidates from outside your own
symbols. A session that is configured but rejected is a failure: the scan
finishes and stores its results, then exits with status 3.

Custom queries need a signed-in screener.in account, and screener.in signs in
through Google in a browser - a flow a scheduled job cannot complete and should
not hold credentials for. Copy the `sessionid` cookie from a signed-in browser
instead and set `SCREENER_SESSION_ID`. When it expires the scan exits with
status 3, and `evening.sh` logs the failed step; sign in again and replace it.
