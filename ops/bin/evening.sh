#!/usr/bin/env bash
# 18:30 IST, weekdays. The nightly batch.
#
# Order matters, and it is the whole point of this script:
#   1. ingest the session      - writes trading_day + the top-3 windows
#   2. score yesterday's calls - needs the completed tape from step 1
#   3. import fills            - matches lots and attributes reasons
#   4. capture                 - compares 3 against 1, so it must come last
#   5. print the day           - what you actually read
#
# Every step is idempotent. If the daemon already reconciled the session, step 1
# finds nothing missing and does nothing; if the daemon never ran, step 1 is what
# saves the day.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

SESSION_DATE="${1:-$(date '+%F')}"
MONTH="${SESSION_DATE:0:7}"

log "evening batch for $SESSION_DATE"

run_step "ingest session" com.stockanalyzer.DailyAnalysisMain daily --date "$SESSION_DATE"
run_step "score predictions" com.stockanalyzer.DailyAnalysisMain evaluate --date "$SESSION_DATE"
run_step "import fills" com.stockanalyzer.TradeJournalMain trades import --broker \
    --from "$SESSION_DATE" --to "$SESSION_DATE"
run_step "capture analysis" com.stockanalyzer.TradeJournalMain capture --month "$MONTH"
run_step "day P&L" com.stockanalyzer.TradeJournalMain pnl --period day --date "$SESSION_DATE"

finish
