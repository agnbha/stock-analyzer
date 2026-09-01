#!/usr/bin/env bash
# Runs Grafana without Docker or Homebrew.
#
# Downloads the standalone macOS build into grafana/.runtime/ (about 100 MB,
# once), installs the SQLite plugin, points it at this repo's database, and
# starts it on http://localhost:3000 (admin/admin).
#
# Nothing is installed system-wide; delete grafana/.runtime/ to undo it all.

set -euo pipefail

GRAFANA_VERSION="${GRAFANA_VERSION:-11.6.0}"
GRAFANA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$GRAFANA_DIR/.." && pwd)"
RUNTIME="$GRAFANA_DIR/.runtime"
HOME_PATH="$RUNTIME/grafana-v$GRAFANA_VERSION"
DB_PATH="${STOCK_ANALYZER_DB:-$REPO_ROOT/data/stock-analyzer.db}"

case "$(uname -m)" in
  arm64) ARCH="darwin-arm64" ;;
  x86_64) ARCH="darwin-amd64" ;;
  *) echo "Unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac

if [[ ! -f "$DB_PATH" ]]; then
  echo "No database at $DB_PATH" >&2
  echo "Run 'DailyAnalysisMain backfill' first, or set STOCK_ANALYZER_DB." >&2
  exit 1
fi

mkdir -p "$RUNTIME"

if [[ ! -f "$HOME_PATH/conf/defaults.ini" ]]; then
  echo "Downloading Grafana $GRAFANA_VERSION for $ARCH (about 100 MB, once)..."
  curl -fSL --progress-bar \
    "https://dl.grafana.com/oss/release/grafana-$GRAFANA_VERSION.$ARCH.tar.gz" \
    -o "$RUNTIME/grafana.tar.gz"
  tar xzf "$RUNTIME/grafana.tar.gz" -C "$RUNTIME"
  rm -f "$RUNTIME/grafana.tar.gz"
fi

# Everything below depends on this file existing; say so plainly rather than
# failing later with a message about homepath.
if [[ ! -f "$HOME_PATH/conf/defaults.ini" ]]; then
  echo "Grafana did not unpack where expected: $HOME_PATH" >&2
  echo "Found instead: $(ls -d "$RUNTIME"/grafana* 2>/dev/null || echo 'nothing')" >&2
  echo "Delete $RUNTIME and re-run, or set GRAFANA_VERSION to a version that exists." >&2
  exit 1
fi

PLUGINS="$RUNTIME/plugins"
mkdir -p "$PLUGINS" "$RUNTIME/data" "$RUNTIME/logs" "$RUNTIME/provisioning/datasources" "$RUNTIME/provisioning/dashboards"

if [[ ! -d "$PLUGINS/frser-sqlite-datasource" ]]; then
  echo "Installing the SQLite datasource plugin..."
  # --homepath is required here too: the CLI shares the server's init path and
  # cannot find conf/defaults.ini without it.
  if ! "$HOME_PATH/bin/grafana" cli --homepath "$HOME_PATH" \
        --pluginsDir "$PLUGINS" plugins install frser-sqlite-datasource; then
    echo "Plugin install failed. Without it the dashboards have no datasource." >&2
    echo "Retry, or install by hand:" >&2
    echo "  $HOME_PATH/bin/grafana cli --homepath $HOME_PATH \\" >&2
    echo "      --pluginsDir $PLUGINS plugins install frser-sqlite-datasource" >&2
    exit 1
  fi
fi

if [[ ! -d "$PLUGINS/frser-sqlite-datasource" ]]; then
  echo "The plugin directory is still empty after a reported success: $PLUGINS" >&2
  exit 1
fi

# The committed provisioning uses the container's mount path; rewrite it with
# the real one for a local run. Generated, so the checked-in copy stays correct
# for docker compose.
sed "s#/var/lib/stock-analyzer/stock-analyzer.db#$DB_PATH#" \
  "$GRAFANA_DIR/provisioning/datasources/sqlite.yaml" \
  > "$RUNTIME/provisioning/datasources/sqlite.yaml"

sed "s#/etc/grafana/dashboards#$GRAFANA_DIR/dashboards#" \
  "$GRAFANA_DIR/provisioning/dashboards/dashboards.yaml" \
  > "$RUNTIME/provisioning/dashboards/dashboards.yaml"

cat > "$RUNTIME/custom.ini" <<INI
[paths]
data = $RUNTIME/data
logs = $RUNTIME/logs
plugins = $PLUGINS
provisioning = $RUNTIME/provisioning

[server]
http_port = 3000

[security]
admin_user = admin
admin_password = admin

[users]
default_theme = dark

[analytics]
reporting_enabled = false
check_for_updates = false
INI

echo
echo "Grafana starting on http://localhost:3000  (admin / admin)"
echo "Dashboards are in the 'Stock Analyzer' folder. Ctrl-C to stop."
echo "Reading: $DB_PATH"
echo
exec "$HOME_PATH/bin/grafana" server --homepath "$HOME_PATH" --config "$RUNTIME/custom.ini"
