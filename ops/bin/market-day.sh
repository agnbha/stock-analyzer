#!/usr/bin/env bash
# 08:58 IST, weekdays. Starts the market-hours daemon (Parts 3 and 4).
#
# The daemon plans the day's alerts at startup, polls every 2.5 minutes through
# the session, reconciles against the authoritative tape after the close, and
# then exits on its own - so this runs once a day and is not a service to keep
# alive. It also checks the trading calendar itself and exits immediately on a
# holiday, so no weekday logic is needed here.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

# launchd cannot put a date in StandardOutPath, so the wrapper owns its logging
# and redirects the whole script - not just the java process - to a dated pair.
# The live view goes to stdout and the log lines to stderr, so they are worth
# separating: one is a dashboard reprinted every tick, the other is the record
# of what happened.
LOG_FILE="$LOG_DIR/market-day-$(date '+%F').log"
ERR_FILE="${LOG_FILE%.log}.err.log"
exec >>"$LOG_FILE" 2>>"$ERR_FILE"

log "starting MarketDayDaemon (view -> $LOG_FILE, log -> $ERR_FILE)" >&2
exec java -cp "$JAR" com.stockanalyzer.MarketDayDaemon "$@"
