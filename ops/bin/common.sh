#!/usr/bin/env bash
# Shared setup for every scheduled job. Sourced, never run directly.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAR="$REPO_ROOT/target/stock-analyzer.jar"
LOG_DIR="$REPO_ROOT/logs"
ENV_FILE="${STOCK_ANALYZER_ENV:-$HOME/.stock-analyzer.env}"

mkdir -p "$LOG_DIR"

# Credentials live outside the repo, so nothing secret can be committed by
# accident. See ops/README.md for the file's shape.
if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

if [[ ! -f "$JAR" ]]; then
  echo "$(date '+%F %T') FATAL  $JAR is missing. Run 'mvn clean package' first." >&2
  exit 1
fi

log() {
  echo "$(date '+%F %T') $*"
}

# Runs one CLI step, logs it, and remembers a failure without aborting the rest
# of the batch: a broken trade import must not cost you the night's analysis.
FAILED_STEPS=()
run_step() {
  local label="$1"
  shift
  log "-> $label"
  if java -cp "$JAR" "$@"; then
    log "   ok"
  else
    local status=$?
    log "   FAILED (exit $status)"
    FAILED_STEPS+=("$label")
  fi
}

finish() {
  if [[ ${#FAILED_STEPS[@]} -eq 0 ]]; then
    log "all steps completed"
    exit 0
  fi
  log "FAILED steps: ${FAILED_STEPS[*]}"
  log "Nothing is lost - every step is idempotent, so re-running catches up."
  exit 1
}
