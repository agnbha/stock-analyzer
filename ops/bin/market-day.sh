#!/usr/bin/env bash
# 08:58 IST, weekdays. Starts the market-hours daemon (Parts 3 and 4).
#
# The daemon plans the day's alerts at startup, polls every 2.5 minutes through
# the session, reconciles against the authoritative tape after the close, and
# then exits on its own - so this runs once a day and is not a service to keep
# alive. It also checks the trading calendar itself and exits immediately on a
# holiday, so no weekday logic is needed here.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

LOG_FILE="$LOG_DIR/market-day-$(date '+%F').log"

# The live view goes to stdout and the log lines to stderr, so they are worth
# separating: one is a dashboard reprinted every tick, the other is the record
# of what happened.
log "starting MarketDayDaemon (view -> $LOG_FILE, log -> ${LOG_FILE%.log}.err.log)"
exec java -cp "$JAR" com.stockanalyzer.MarketDayDaemon "$@" \
    >>"$LOG_FILE" 2>>"${LOG_FILE%.log}.err.log"
