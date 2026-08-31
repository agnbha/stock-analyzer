#!/usr/bin/env bash
# Saturday 10:00 IST. Refreshes the statistical prior and reviews the week.
#
# The prior is recomputed weekly rather than nightly on purpose: a single
# session barely moves a 60-day window, and a set of alert windows that only
# changes once a week is one you can actually notice changing. Nightly is
# harmless if you prefer it - the job is a single scan.
#
# This has to finish before Monday's open, because the daemon reads hot_window
# when it plans the day's alerts.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

LAST_FRIDAY="$(date -v-fri '+%F' 2>/dev/null || date -d 'last friday' '+%F')"

log "weekly maintenance, week ending $LAST_FRIDAY"

run_step "recompute time-of-day prior" com.stockanalyzer.DailyAnalysisMain hot-windows --lookback 60
run_step "backfill any gaps" com.stockanalyzer.DailyAnalysisMain backfill \
    --from "$(date -v-14d '+%F' 2>/dev/null || date -d '14 days ago' '+%F')" --to "$LAST_FRIDAY"
run_step "week P&L" com.stockanalyzer.TradeJournalMain pnl --period week --of "$LAST_FRIDAY"

finish
