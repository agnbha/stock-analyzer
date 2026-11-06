#!/usr/bin/env bash
# 18:30 IST, weekdays. The nightly batch.
#
# Order matters, and it is the whole point of this script:
#   0. consolidate             - re-fetch the now-complete day and clear staging
#   1. ingest the session      - writes trading_day + the top-3 windows
#   1b. write the shortlist    - the Watchlist's picks, kept so the page can score them on the next session
#   2. score yesterday's calls - needs the completed tape from step 1
#   3. import fills            - matches lots and attributes reasons
#   4. capture                 - compares 3 against 1, so it must come last
#   5. balance                 - what the broker says the account now holds
#   6. print the day           - what you actually read
#   7. CAN SLIM scan           - needs the day's closing daily bar, so after the close
#
# Every step is idempotent. If the daemon already reconciled the session, step 1
# finds nothing missing and does nothing; if the daemon never ran, step 1 is what
# saves the day.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

SESSION_DATE="${1:-$(date '+%F')}"
MONTH="${SESSION_DATE:0:7}"

log "evening batch for $SESSION_DATE"

# Usually a no-op: the daemon consolidates and clears its own staging when it
# shuts down at the close. This is the safety net for the days it did not run -
# machine asleep, a crash, or a SIGKILL that skipped its shutdown - and costs
# nothing when there is nothing to do.
run_step "consolidate live session" com.stockanalyzer.DailyAnalysisMain consolidate \
    --date "$SESSION_DATE"
run_step "ingest session" com.stockanalyzer.DailyAnalysisMain daily --date "$SESSION_DATE"
# Writes down tonight's Watchlist shortlist (the highest trend scores among complete sessions) so the page can
# score it against the session that follows. It reads only what the ingest above has stored, so it sits
# straight after it and never waits on the slow CAN SLIM scan at the end.
run_step "log watchlist shortlist" com.stockanalyzer.DailyAnalysisMain watchlist --date "$SESSION_DATE"
run_step "score predictions" com.stockanalyzer.DailyAnalysisMain evaluate --date "$SESSION_DATE"
run_step "import fills" com.stockanalyzer.TradeJournalMain trades import --broker \
    --from "$SESSION_DATE" --to "$SESSION_DATE"
# The notes written on the Grafana charts live in Grafana's own file; keep a copy with the journal. Harmless
# where Grafana is not installed: it says so and carries on.
run_step "sync chart notes" com.stockanalyzer.TradeJournalMain chartnotes sync
run_step "capture analysis" com.stockanalyzer.TradeJournalMain capture --month "$MONTH"
# Read the balance from the broker rather than carrying a number forward. It runs
# after the fills are in so the day's ledger has settled, and it is what makes the
# overview's account value a reading instead of an estimate.
run_step "record broker balance" com.stockanalyzer.TradeJournalMain trades balance \
    --date "$SESSION_DATE"
run_step "day P&L" com.stockanalyzer.TradeJournalMain pnl --period day --date "$SESSION_DATE"
# Without a screener.in session the query is skipped and the scan says so; holdings
# and the configured symbols are still scored (fundamentals from public company
# pages). Exit status 3 means a configured session was rejected: sign in again.
# --market also prices every listed NSE/BSE equity and ranks them together for RS. That is
# ~2,800 candle requests at the ingest rate limit (about 25 minutes), plus up to
# canslim.fundamentals.max.pages.per.run company pages at 12 s each for the RS leaders, so
# it runs last. Leaders whose page is not read tonight are picked up on later nights.
# The company details on Candles and Calls (fundamentals, quarterly revenue and profit, shareholding, about)
# come from screener.in's public pages. A few of the stalest are read each night, 12 s apart, ahead of the
# long scan so a slow scan cannot starve them; a company is read again after company.profile.refresh.days.
run_step "refresh company profiles" com.stockanalyzer.CanSlimMain profiles
run_step "CAN SLIM scan" com.stockanalyzer.CanSlimMain scan --market

finish
