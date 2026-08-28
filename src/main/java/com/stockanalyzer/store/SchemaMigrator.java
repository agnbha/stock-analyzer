package com.stockanalyzer.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies versioned DDL in order and records what has been applied. Migrations
 * are append-only: never edit a shipped one, add the next version instead.
 */
public final class SchemaMigrator {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);

    private final Database database;

    public SchemaMigrator(Database database) {
        this.database = database;
    }

    public void migrate() {
        database.inTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS schema_migration (
                          version INTEGER PRIMARY KEY,
                          applied_at INTEGER NOT NULL
                        )""");
            }
            int current = currentVersion(connection);
            for (Map.Entry<Integer, List<String>> migration : migrations().entrySet()) {
                if (migration.getKey() <= current) {
                    continue;
                }
                log.info("Applying schema migration v{}", migration.getKey());
                try (Statement statement = connection.createStatement()) {
                    for (String ddl : migration.getValue()) {
                        statement.execute(ddl);
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO schema_migration (version, applied_at) VALUES (?, ?)")) {
                    insert.setInt(1, migration.getKey());
                    insert.setLong(2, System.currentTimeMillis() / 1000);
                    insert.executeUpdate();
                }
            }
        });
    }

    private int currentVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_migration")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private Map<Integer, List<String>> migrations() {
        Map<Integer, List<String>> all = new LinkedHashMap<>();
        all.put(1, v1());
        all.put(2, v2());
        all.put(3, v3());
        all.put(4, v4());
        return all;
    }

    /** v1: candle history, sessions and the gain opportunities derived from them. */
    private List<String> v1() {
        List<String> ddl = new ArrayList<>();
        ddl.add("""
                CREATE TABLE instrument (
                  id INTEGER PRIMARY KEY,
                  symbol TEXT NOT NULL,
                  exchange TEXT NOT NULL,
                  segment TEXT NOT NULL,
                  name TEXT,
                  UNIQUE (symbol, exchange, segment)
                )""");
        ddl.add("""
                CREATE TABLE trading_day (
                  id INTEGER PRIMARY KEY,
                  instrument_id INTEGER NOT NULL REFERENCES instrument(id),
                  session_date TEXT NOT NULL,
                  interval_minutes INTEGER NOT NULL,
                  open REAL, high REAL, low REAL, close REAL, volume INTEGER,
                  day_change_pct REAL,
                  candle_count INTEGER NOT NULL,
                  first_candle_ts INTEGER, last_candle_ts INTEGER,
                  source TEXT NOT NULL,
                  ingested_at INTEGER NOT NULL,
                  UNIQUE (instrument_id, session_date, interval_minutes)
                )""");
        ddl.add("CREATE INDEX ix_trading_day_date ON trading_day (session_date)");
        ddl.add("""
                CREATE TABLE gain_opportunity (
                  id INTEGER PRIMARY KEY,
                  trading_day_id INTEGER NOT NULL REFERENCES trading_day(id) ON DELETE CASCADE,
                  detector_version TEXT NOT NULL,
                  rank INTEGER NOT NULL,
                  entry_ts INTEGER NOT NULL,
                  exit_ts INTEGER NOT NULL,
                  entry_price REAL NOT NULL,
                  exit_price REAL NOT NULL,
                  gain_pct REAL NOT NULL,
                  duration_minutes INTEGER NOT NULL,
                  UNIQUE (trading_day_id, detector_version, rank)
                )""");
        ddl.add("CREATE INDEX ix_opp_gain ON gain_opportunity (gain_pct DESC)");
        ddl.add("""
                CREATE TABLE candle (
                  instrument_id INTEGER NOT NULL REFERENCES instrument(id),
                  interval_minutes INTEGER NOT NULL,
                  ts_epoch INTEGER NOT NULL,
                  open REAL, high REAL, low REAL, close REAL, volume INTEGER,
                  PRIMARY KEY (instrument_id, interval_minutes, ts_epoch)
                ) WITHOUT ROWID""");
        ddl.add("""
                CREATE TABLE non_trading_day (
                  session_date TEXT PRIMARY KEY,
                  reason TEXT,
                  observed_at INTEGER NOT NULL
                )""");
        ddl.add("""
                CREATE TABLE ingestion_run (
                  id INTEGER PRIMARY KEY,
                  started_at INTEGER NOT NULL,
                  finished_at INTEGER,
                  session_date TEXT,
                  mode TEXT,
                  requested INTEGER, succeeded INTEGER, failed INTEGER,
                  status TEXT
                )""");
        ddl.add("""
                CREATE TABLE ingestion_failure (
                  id INTEGER PRIMARY KEY,
                  run_id INTEGER NOT NULL REFERENCES ingestion_run(id),
                  symbol TEXT NOT NULL,
                  session_date TEXT NOT NULL,
                  error_type TEXT, message TEXT, attempts INTEGER
                )""");
        ddl.add("""
                CREATE TABLE hot_window (
                  id INTEGER PRIMARY KEY,
                  instrument_id INTEGER REFERENCES instrument(id),
                  bucket_start_minute INTEGER NOT NULL,
                  bucket_minutes INTEGER NOT NULL,
                  lookback_days INTEGER NOT NULL,
                  hits INTEGER NOT NULL,
                  sessions INTEGER NOT NULL,
                  hit_rate REAL NOT NULL,
                  hit_rate_lcb REAL NOT NULL,
                  mean_gain_pct REAL,
                  median_gain_pct REAL,
                  computed_at INTEGER NOT NULL,
                  UNIQUE (instrument_id, bucket_start_minute, bucket_minutes, lookback_days)
                )""");
        ddl.add("""
                CREATE TABLE event (
                  id INTEGER PRIMARY KEY,
                  instrument_id INTEGER NOT NULL REFERENCES instrument(id),
                  ts_epoch INTEGER NOT NULL,
                  session_date TEXT NOT NULL,
                  event_type TEXT NOT NULL,
                  strength REAL,
                  detector_version TEXT NOT NULL,
                  UNIQUE (instrument_id, ts_epoch, event_type, detector_version)
                )""");
        ddl.add("""
                CREATE TABLE model_version (
                  id INTEGER PRIMARY KEY,
                  name TEXT NOT NULL UNIQUE,
                  trained_at INTEGER,
                  train_from TEXT, train_to TEXT,
                  metrics_json TEXT,
                  active INTEGER NOT NULL DEFAULT 0
                )""");
        ddl.add("""
                CREATE TABLE prediction (
                  id INTEGER PRIMARY KEY,
                  model_version_id INTEGER NOT NULL REFERENCES model_version(id),
                  instrument_id INTEGER NOT NULL REFERENCES instrument(id),
                  ts_epoch INTEGER NOT NULL,
                  session_date TEXT NOT NULL,
                  signal TEXT NOT NULL,
                  probability REAL NOT NULL,
                  horizon_minutes INTEGER NOT NULL,
                  realized_return_pct REAL,
                  UNIQUE (model_version_id, instrument_id, ts_epoch)
                )""");
        ddl.add("""
                CREATE TABLE alert_schedule (
                  id INTEGER PRIMARY KEY,
                  session_date TEXT NOT NULL,
                  fire_at_epoch INTEGER NOT NULL,
                  instrument_id INTEGER REFERENCES instrument(id),
                  symbol TEXT,
                  rule TEXT NOT NULL,
                  payload TEXT,
                  status TEXT NOT NULL
                )""");
        ddl.add("""
                CREATE TABLE alert_log (
                  id INTEGER PRIMARY KEY,
                  session_date TEXT NOT NULL,
                  fired_at_epoch INTEGER NOT NULL,
                  symbol TEXT,
                  rule TEXT NOT NULL,
                  severity TEXT NOT NULL,
                  title TEXT NOT NULL,
                  message TEXT NOT NULL,
                  idempotency_key TEXT NOT NULL UNIQUE
                )""");
        return ddl;
    }


    /**
     * v4: why a trade was taken, what the account is worth, and the views the
     * dashboards read.
     *
     * <p>The views exist so reporting logic lives in one place rather than being
     * retyped into every Grafana panel; they are also the documentation of what
     * each dashboard number actually means.
     */
    private List<String> v4() {
        List<String> ddl = new ArrayList<>();
        ddl.add("""
                CREATE TABLE trade_reason (
                  id INTEGER PRIMARY KEY,
                  trade_id INTEGER NOT NULL REFERENCES trade(id) ON DELETE CASCADE,
                  reason_code TEXT NOT NULL,
                  reason_source TEXT NOT NULL,     -- MANUAL | EVENT | ALERT | MODEL | UNEXPLAINED
                  detail TEXT,
                  recorded_at INTEGER NOT NULL,
                  UNIQUE (trade_id, reason_code, reason_source)
                )""");
        ddl.add("CREATE INDEX ix_trade_reason_code ON trade_reason (reason_code)");
        ddl.add("""
                CREATE TABLE account_balance (
                  session_date TEXT PRIMARY KEY,
                  cash REAL NOT NULL,
                  invested REAL,
                  total REAL,
                  source TEXT NOT NULL,            -- 'manual' | 'broker'
                  recorded_at INTEGER NOT NULL
                )""");

        // Turnover is the money put at risk on a fill - the "bet amount".
        ddl.add("""
                CREATE VIEW v_bet AS
                SELECT t.id                        AS trade_id,
                       t.session_date              AS session_date,
                       t.executed_ts               AS executed_ts,
                       i.symbol                    AS symbol,
                       t.side                      AS side,
                       t.product                   AS product,
                       t.quantity                  AS quantity,
                       t.price                     AS price,
                       t.quantity * t.price        AS bet_amount,
                       COALESCE(t.charges_total, 0) AS charges,
                       COALESCE(json_extract(t.charges_json, '$.brokerage'), 0) AS brokerage,
                       COALESCE(json_extract(t.charges_json, '$.stt'), 0)
                         + COALESCE(json_extract(t.charges_json, '$.stamp'), 0)
                         + COALESCE(json_extract(t.charges_json, '$.gst'), 0)      AS taxes,
                       COALESCE(json_extract(t.charges_json, '$.exchange'), 0)
                         + COALESCE(json_extract(t.charges_json, '$.sebi'), 0)     AS fees
                FROM trade t JOIN instrument i ON i.id = t.instrument_id""");

        ddl.add("""
                CREATE VIEW v_daily_pnl AS
                SELECT d.session_date                       AS session_date,
                       COALESCE(b.trades, 0)                AS trades,
                       COALESCE(b.bet_amount, 0)            AS bet_amount,
                       COALESCE(b.charges, 0)               AS charges,
                       COALESCE(b.taxes, 0)                 AS taxes,
                       COALESCE(b.brokerage, 0)             AS brokerage,
                       COALESCE(b.fees, 0)                  AS fees,
                       COALESCE(l.gross_pnl, 0)             AS gross_pnl,
                       COALESCE(l.net_pnl, 0)               AS net_pnl,
                       COALESCE(l.closed_lots, 0)           AS closed_lots,
                       COALESCE(l.wins, 0)                  AS wins
                FROM (SELECT DISTINCT session_date FROM trade) d
                LEFT JOIN (SELECT session_date,
                                  COUNT(*)          AS trades,
                                  SUM(bet_amount)   AS bet_amount,
                                  SUM(charges)      AS charges,
                                  SUM(taxes)        AS taxes,
                                  SUM(brokerage)    AS brokerage,
                                  SUM(fees)         AS fees
                           FROM v_bet GROUP BY session_date) b ON b.session_date = d.session_date
                LEFT JOIN (SELECT s.session_date            AS session_date,
                                  SUM(r.gross_pnl)          AS gross_pnl,
                                  SUM(r.net_pnl)            AS net_pnl,
                                  COUNT(*)                  AS closed_lots,
                                  SUM(CASE WHEN r.net_pnl > 0 THEN 1 ELSE 0 END) AS wins
                           FROM realized_lot r JOIN trade s ON s.id = r.sell_trade_id
                           GROUP BY s.session_date) l ON l.session_date = d.session_date""");

        // Equity: the last recorded cash balance if there is one on or before the
        // day, otherwise cumulative net P&L on its own. Both are shown, because a
        // curve built only from P&L is not the same claim as a real balance.
        ddl.add("""
                CREATE VIEW v_equity_curve AS
                SELECT p.session_date AS session_date,
                       (SELECT SUM(net_pnl) FROM v_daily_pnl x WHERE x.session_date <= p.session_date)
                                      AS realized_net_cumulative,
                       (SELECT cash FROM account_balance a
                         WHERE a.session_date <= p.session_date
                         ORDER BY a.session_date DESC LIMIT 1) AS recorded_cash,
                       (SELECT a.session_date FROM account_balance a
                         WHERE a.session_date <= p.session_date
                         ORDER BY a.session_date DESC LIMIT 1) AS cash_as_of
                FROM v_daily_pnl p""");

        ddl.add("""
                CREATE VIEW v_daily_candles AS
                SELECT t.session_date AS session_date,
                       i.symbol       AS symbol,
                       t.first_candle_ts AS ts_epoch,
                       t.open, t.high, t.low, t.close, t.volume
                FROM trading_day t JOIN instrument i ON i.id = t.instrument_id""");

        ddl.add("""
                CREATE VIEW v_weekly_candles AS
                WITH ranked AS (
                  SELECT i.symbol AS symbol,
                         strftime('%Y-%W', t.session_date) AS week,
                         t.session_date, t.first_candle_ts, t.open, t.high, t.low, t.close, t.volume,
                         ROW_NUMBER() OVER (PARTITION BY i.symbol, strftime('%Y-%W', t.session_date)
                                            ORDER BY t.session_date)      AS first_in_week,
                         ROW_NUMBER() OVER (PARTITION BY i.symbol, strftime('%Y-%W', t.session_date)
                                            ORDER BY t.session_date DESC) AS last_in_week
                  FROM trading_day t JOIN instrument i ON i.id = t.instrument_id)
                SELECT symbol,
                       week,
                       MIN(session_date)                                        AS session_date,
                       MIN(first_candle_ts)                                     AS ts_epoch,
                       MAX(CASE WHEN first_in_week = 1 THEN open END)           AS open,
                       MAX(high)                                                AS high,
                       MIN(low)                                                 AS low,
                       MAX(CASE WHEN last_in_week = 1 THEN close END)           AS close,
                       SUM(volume)                                              AS volume
                FROM ranked GROUP BY symbol, week""");

        ddl.add("""
                CREATE VIEW v_trade_markers AS
                SELECT t.executed_ts AS ts_epoch,
                       t.session_date AS session_date,
                       i.symbol       AS symbol,
                       t.side         AS side,
                       t.product      AS product,
                       t.quantity     AS quantity,
                       t.price        AS price,
                       t.quantity * t.price AS bet_amount
                FROM trade t JOIN instrument i ON i.id = t.instrument_id""");

        // Frequency of each stated reason, and whether trades taken for that
        // reason actually made money.
        ddl.add("""
                CREATE VIEW v_reason_frequency AS
                SELECT r.reason_code   AS reason_code,
                       r.reason_source AS reason_source,
                       t.side          AS side,
                       t.session_date  AS session_date,
                       COUNT(*)        AS occurrences,
                       SUM(t.quantity * t.price) AS bet_amount,
                       (SELECT COALESCE(SUM(l.net_pnl), 0) FROM realized_lot l
                         WHERE l.buy_trade_id = t.id OR l.sell_trade_id = t.id) AS net_pnl
                FROM trade_reason r JOIN trade t ON t.id = r.trade_id
                GROUP BY r.reason_code, r.reason_source, t.side, t.session_date""");
        return ddl;
    }


    /** v3: liveness, so a silently dead daemon is detectable. */
    private List<String> v3() {
        return List.of("""
                CREATE TABLE monitor_heartbeat (
                  id INTEGER PRIMARY KEY CHECK (id = 1),
                  session_date TEXT,
                  last_tick_epoch INTEGER NOT NULL,
                  symbols_tracked INTEGER,
                  degraded INTEGER NOT NULL DEFAULT 0,
                  note TEXT
                )""");
    }

    /** v2: the trade journal and everything derived from it. */
    private List<String> v2() {
        List<String> ddl = new ArrayList<>();
        ddl.add("""
                CREATE TABLE trade (
                  id INTEGER PRIMARY KEY,
                  instrument_id INTEGER NOT NULL REFERENCES instrument(id),
                  broker_trade_id TEXT NOT NULL UNIQUE,
                  order_id TEXT,
                  session_date TEXT NOT NULL,
                  executed_ts INTEGER NOT NULL,
                  side TEXT NOT NULL,
                  quantity INTEGER NOT NULL,
                  price REAL NOT NULL,
                  product TEXT NOT NULL,
                  charges_total REAL,
                  charges_json TEXT,
                  charges_source TEXT NOT NULL,
                  source TEXT NOT NULL,
                  imported_at INTEGER NOT NULL,
                  notes TEXT
                )""");
        ddl.add("CREATE INDEX ix_trade_session ON trade (session_date, instrument_id)");
        ddl.add("""
                CREATE TABLE realized_lot (
                  id INTEGER PRIMARY KEY,
                  instrument_id INTEGER NOT NULL REFERENCES instrument(id),
                  buy_trade_id INTEGER NOT NULL REFERENCES trade(id),
                  sell_trade_id INTEGER NOT NULL REFERENCES trade(id),
                  product TEXT NOT NULL,
                  quantity INTEGER NOT NULL,
                  buy_price REAL NOT NULL,
                  sell_price REAL NOT NULL,
                  opened_ts INTEGER NOT NULL,
                  closed_ts INTEGER NOT NULL,
                  holding_minutes INTEGER NOT NULL,
                  gross_pnl REAL NOT NULL,
                  charges_allocated REAL NOT NULL,
                  net_pnl REAL NOT NULL,
                  return_pct REAL NOT NULL,
                  UNIQUE (buy_trade_id, sell_trade_id)
                )""");
        ddl.add("CREATE INDEX ix_lot_closed ON realized_lot (closed_ts)");
        ddl.add("""
                CREATE TABLE open_position (
                  instrument_id INTEGER NOT NULL REFERENCES instrument(id),
                  product TEXT NOT NULL,
                  quantity INTEGER NOT NULL,
                  avg_cost REAL NOT NULL,
                  opened_ts INTEGER NOT NULL,
                  last_updated INTEGER NOT NULL,
                  PRIMARY KEY (instrument_id, product)
                )""");
        ddl.add("""
                CREATE TABLE pnl_period (
                  id INTEGER PRIMARY KEY,
                  period_type TEXT NOT NULL,
                  period_start TEXT NOT NULL,
                  period_end TEXT NOT NULL,
                  instrument_id INTEGER,
                  product TEXT,
                  trades INTEGER NOT NULL,
                  closed_lots INTEGER NOT NULL,
                  wins INTEGER NOT NULL,
                  losses INTEGER NOT NULL,
                  win_rate REAL,
                  gross_pnl REAL NOT NULL,
                  charges REAL NOT NULL,
                  net_pnl REAL NOT NULL,
                  turnover REAL NOT NULL,
                  charges_pct_turnover REAL,
                  avg_win REAL, avg_loss REAL, profit_factor REAL,
                  best_lot_pnl REAL, worst_lot_pnl REAL,
                  unrealized_end REAL,
                  computed_at INTEGER NOT NULL,
                  UNIQUE (period_type, period_start, instrument_id, product)
                )""");
        ddl.add("""
                CREATE TABLE trade_attribution (
                  trade_id INTEGER PRIMARY KEY REFERENCES trade(id),
                  gain_opportunity_id INTEGER REFERENCES gain_opportunity(id),
                  alert_log_id INTEGER REFERENCES alert_log(id),
                  entry_lag_minutes INTEGER,
                  capture_pct REAL
                )""");
        return ddl;
    }
}
