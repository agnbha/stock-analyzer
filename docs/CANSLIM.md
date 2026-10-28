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
2. **Widen.** Open positions and the configured `symbols.txt` universe join the
   screened companies. They are checked for sell signals and widen the field the
   RS rating is ranked against. Their own C, A and I come from their public
   screener.in company pages (cached for `canslim.fundamentals.cache.days`, 7 by
   default, since the numbers change once a quarter), so they are scored like any
   other stock - and a held or universe stock whose fundamentals pass both C and
   A is a buy candidate too, not just a sell check.
3. **Price.** About a year of daily candles per symbol, plus the market proxy
   (`NIFTYBEES`). The daily interval allows years per request, so this is one
   request per symbol, through the same rate limiter as everything else. A
   session still in progress is left out: half a day's volume would read as a
   collapse.
4. **Score and decide.** Each letter, then a signal (below).
5. **Store.** Daily bars go to `daily_bar`, and the run and every score go to
   `canslim_run` / `canslim_score`, so the dashboard can show today's lists and
   each stock's history of readings.

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
