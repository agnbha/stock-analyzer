#!/usr/bin/env bash
# 19:30 and 22:00 IST, weekdays. The institutional flows, once NSE has published them.
#
# NSE puts a day's FII and DII cash-market figures and its participant-wise open interest up that evening, well after
# the 15:35 evening batch has run (which reads them too, as part of `CanSlimMain markets`). On its own the batch reads
# each day's figures the next afternoon, so the Institutional Investment page is always a trading day behind, and
# because NSE keeps only the latest day's FII/DII figures, an evening batch that did not run would lose a day for good.
# This reads only the institutional series (a dozen small requests, no broker login), twice, to catch a late
# publication; most nights the second run finds nothing new. Idempotent, like every step.
#
# NSDL's FPI figures for a trading day are reported the day after, so they still arrive with the next afternoon's batch.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

log "institutional flows"
run_step "read institutional flows" com.stockanalyzer.CanSlimMain markets --institutional

finish
