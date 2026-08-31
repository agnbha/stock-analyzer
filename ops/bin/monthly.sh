#!/usr/bin/env bash
# 1st of the month, 10:00 IST. The books for the month just ended.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

LAST_MONTH="$(date -v-1m '+%Y-%m' 2>/dev/null || date -d 'last month' '+%Y-%m')"
STATEMENT="$REPO_ROOT/statements/$LAST_MONTH.csv"
mkdir -p "$(dirname "$STATEMENT")"

log "monthly close for $LAST_MONTH"

run_step "month P&L" com.stockanalyzer.TradeJournalMain pnl --period month --month "$LAST_MONTH"
run_step "capture ratio" com.stockanalyzer.TradeJournalMain capture --month "$LAST_MONTH"
run_step "reason frequency" com.stockanalyzer.TradeJournalMain trades reasons --month "$LAST_MONTH"
run_step "export statement" com.stockanalyzer.TradeJournalMain statement \
    --from "$LAST_MONTH-01" --to "$(date -v1d -v-1m -v+1m -v-1d '+%F' 2>/dev/null || date -d "$LAST_MONTH-01 +1 month -1 day" '+%F')" \
    --out "$STATEMENT"

log "reminder: record the account balance so the equity curve stays honest -"
log "  java -cp \$JAR com.stockanalyzer.TradeJournalMain trades balance --cash <amount>"

finish
