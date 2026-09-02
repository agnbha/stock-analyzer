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
        all.put(5, v5());
        all.put(6, v6());
        all.put(7, v7());
        all.put(8, v8());
        all.put(9, v9());
        all.put(10, v10());
        all.put(11, v11());
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



    /**
     * v5: the intraday view the dashboards need to show a session at the
     * resolution it was actually analysed at.
     *
     * <p>Daily bars cannot show a window that opened at 09:16 and closed at
     * 09:33 - the top-3 windows only exist at minute resolution, so marking
     * them needs the minute candles.
     */
    private List<String> v5() {
        List<String> ddl = new ArrayList<>();

        // The exchange's local date for each candle. Timestamps are stored UTC;
        // IST is UTC+5:30, matching the session_date written by ingestion.
        ddl.add("""
                CREATE VIEW v_intraday_candles AS
                SELECT i.symbol            AS symbol,
                       c.ts_epoch          AS ts_epoch,
                       c.interval_minutes  AS interval_minutes,
                       date(c.ts_epoch, 'unixepoch', '+5 hours', '+30 minutes') AS session_date,
                       c.open, c.high, c.low, c.close, c.volume
                FROM candle c JOIN instrument i ON i.id = c.instrument_id""");

        // The Part 1 deliverable, in a shape a chart can plot: one row per
        // window, carrying both ends so entry and exit can be marked separately.
        ddl.add("""
                CREATE VIEW v_opportunity_markers AS
                SELECT i.symbol             AS symbol,
                       t.session_date       AS session_date,
                       o.detector_version   AS detector_version,
                       o.rank               AS rank,
                       o.entry_ts, o.exit_ts,
                       o.entry_price, o.exit_price,
                       o.gain_pct, o.duration_minutes
                FROM gain_opportunity o
                JOIN trading_day t ON t.id = o.trading_day_id
                JOIN instrument i  ON i.id = t.instrument_id""");
        return ddl;
    }



    /**
     * v6: the day-to-day trend indicators.
     *
     * <p>Each is a genuine indicator with a stated definition, not a label. Two
     * caveats live in the SQL rather than in a comment somewhere else: averages
     * are null until their window is actually full, so a 50-day average never
     * shows a 12-day one wearing its name; and RSI is the simple-average
     * (Cutler) variant rather than Wilder's recursive smoothing, which a view
     * cannot express.
     */
    private List<String> v6() {
        List<String> ddl = new ArrayList<>();

        ddl.add("""
                CREATE VIEW v_symbol_trend AS
                SELECT symbol, session_date, ts_epoch, open, high, low, close, volume,
                       day_change_pct, sma5, sma20, sma50, rsi14,
                       CASE WHEN sma20 IS NULL THEN NULL
                            WHEN close > sma20 AND sma5 > sma20 THEN 2
                            WHEN close < sma20 AND sma5 < sma20 THEN 0
                            ELSE 1 END AS trend_score
                FROM (
                  SELECT symbol, session_date, ts_epoch, open, high, low, close, volume,
                         day_change_pct,
                         CASE WHEN COUNT(close) OVER w5  < 5  THEN NULL
                              ELSE AVG(close) OVER w5  END AS sma5,
                         CASE WHEN COUNT(close) OVER w20 < 20 THEN NULL
                              ELSE AVG(close) OVER w20 END AS sma20,
                         CASE WHEN COUNT(close) OVER w50 < 50 THEN NULL
                              ELSE AVG(close) OVER w50 END AS sma50,
                         CASE WHEN COUNT(gain) OVER w14 < 14 THEN NULL
                              WHEN AVG(loss) OVER w14 = 0 THEN 100.0
                              ELSE 100.0 - 100.0 / (1 + (AVG(gain) OVER w14) / (AVG(loss) OVER w14))
                         END AS rsi14
                  FROM (
                    SELECT i.symbol AS symbol, t.session_date AS session_date,
                           t.first_candle_ts AS ts_epoch,
                           t.open, t.high, t.low, t.close, t.volume, t.day_change_pct,
                           MAX(t.close - LAG(t.close) OVER p, 0) AS gain,
                           MAX(LAG(t.close) OVER p - t.close, 0) AS loss
                    FROM trading_day t JOIN instrument i ON i.id = t.instrument_id
                    WINDOW p AS (PARTITION BY i.symbol ORDER BY t.session_date)
                  )
                  WINDOW w5  AS (PARTITION BY symbol ORDER BY session_date
                                 ROWS BETWEEN 4 PRECEDING AND CURRENT ROW),
                         w14 AS (PARTITION BY symbol ORDER BY session_date
                                 ROWS BETWEEN 13 PRECEDING AND CURRENT ROW),
                         w20 AS (PARTITION BY symbol ORDER BY session_date
                                 ROWS BETWEEN 19 PRECEDING AND CURRENT ROW),
                         w50 AS (PARTITION BY symbol ORDER BY session_date
                                 ROWS BETWEEN 49 PRECEDING AND CURRENT ROW)
                )""");

        // Classic floor-trader pivots from the previous session, plus the
        // rolling extremes price has actually had to fight through.
        ddl.add("""
                CREATE VIEW v_support_resistance AS
                SELECT symbol, session_date, ts_epoch, close,
                       pivot,
                       2 * pivot - prev_low                AS r1,
                       pivot + (prev_high - prev_low)      AS r2,
                       2 * pivot - prev_high               AS s1,
                       pivot - (prev_high - prev_low)      AS s2,
                       high_20, low_20, high_60, low_60
                FROM (
                  SELECT i.symbol AS symbol, t.session_date AS session_date,
                         t.first_candle_ts AS ts_epoch, t.close,
                         (LAG(t.high) OVER p + LAG(t.low) OVER p + LAG(t.close) OVER p) / 3.0 AS pivot,
                         LAG(t.high) OVER p AS prev_high,
                         LAG(t.low)  OVER p AS prev_low,
                         MAX(t.high) OVER w20 AS high_20,
                         MIN(t.low)  OVER w20 AS low_20,
                         MAX(t.high) OVER w60 AS high_60,
                         MIN(t.low)  OVER w60 AS low_60
                  FROM trading_day t JOIN instrument i ON i.id = t.instrument_id
                  WINDOW p   AS (PARTITION BY i.symbol ORDER BY t.session_date),
                         w20 AS (PARTITION BY i.symbol ORDER BY t.session_date
                                 ROWS BETWEEN 19 PRECEDING AND CURRENT ROW),
                         w60 AS (PARTITION BY i.symbol ORDER BY t.session_date
                                 ROWS BETWEEN 59 PRECEDING AND CURRENT ROW)
                )""");

        // Breadth: how much of the tracked universe rose. The oldest session has
        // no prior close, so it contributes nothing and is excluded.
        ddl.add("""
                CREATE VIEW v_market_breadth AS
                SELECT session_date,
                       MIN(first_candle_ts)                                        AS ts_epoch,
                       COUNT(*)                                                    AS symbols,
                       SUM(CASE WHEN day_change_pct > 0 THEN 1 ELSE 0 END)         AS advancing,
                       SUM(CASE WHEN day_change_pct < 0 THEN 1 ELSE 0 END)         AS declining,
                       100.0 * SUM(CASE WHEN day_change_pct > 0 THEN 1 ELSE 0 END)
                             / COUNT(*)                                            AS advancing_pct,
                       AVG(day_change_pct)                                         AS mean_change_pct
                FROM trading_day
                WHERE day_change_pct IS NOT NULL
                GROUP BY session_date""");

        /*
         * Beta against an equal-weighted basket of the tracked symbols, because
         * no index series is stored. That is a proxy for the market, not NIFTY -
         * the number is comparable between these symbols but not with a
         * published beta.
         */
        ddl.add("""
                CREATE VIEW v_symbol_beta AS
                SELECT s.symbol                                                       AS symbol,
                       COUNT(*)                                                       AS sessions,
                       (COUNT(*) * SUM(s.sret * m.mean_change_pct)
                          - SUM(s.sret) * SUM(m.mean_change_pct))
                       / NULLIF(COUNT(*) * SUM(m.mean_change_pct * m.mean_change_pct)
                          - SUM(m.mean_change_pct) * SUM(m.mean_change_pct), 0)       AS beta
                FROM (SELECT i.symbol AS symbol, t.session_date AS session_date,
                             t.day_change_pct AS sret
                      FROM trading_day t JOIN instrument i ON i.id = t.instrument_id
                      WHERE t.day_change_pct IS NOT NULL) s
                JOIN v_market_breadth m ON m.session_date = s.session_date
                GROUP BY s.symbol""");
        return ddl;
    }



    /**
     * v7: derive the daily change from the close series instead of reading the
     * stored column.
     *
     * <p>{@code trading_day.day_change_pct} is filled at ingest time from
     * whatever previous close was already in the database, so it is null when a
     * session was written before the one preceding it - an artefact of backfill
     * order. Breadth and beta describe the market and must not depend on the
     * order rows happened to arrive in, so they now compute the change with
     * LAG over the stored closes.
     */
    private List<String> v7() {
        List<String> ddl = new ArrayList<>();
        ddl.add("DROP VIEW IF EXISTS v_symbol_beta");
        ddl.add("DROP VIEW IF EXISTS v_market_breadth");

        ddl.add("""
                CREATE VIEW v_daily_change AS
                SELECT symbol, session_date, ts_epoch, close, change_pct
                FROM (
                  SELECT i.symbol AS symbol, t.session_date AS session_date,
                         t.first_candle_ts AS ts_epoch, t.close AS close,
                         CASE WHEN LAG(t.close) OVER p IS NULL OR LAG(t.close) OVER p = 0 THEN NULL
                              ELSE (t.close - LAG(t.close) OVER p) / LAG(t.close) OVER p * 100.0
                         END AS change_pct
                  FROM trading_day t JOIN instrument i ON i.id = t.instrument_id
                  WINDOW p AS (PARTITION BY i.symbol ORDER BY t.session_date)
                )
                WHERE change_pct IS NOT NULL""");

        ddl.add("""
                CREATE VIEW v_market_breadth AS
                SELECT session_date,
                       MIN(ts_epoch)                                        AS ts_epoch,
                       COUNT(*)                                             AS symbols,
                       SUM(CASE WHEN change_pct > 0 THEN 1 ELSE 0 END)      AS advancing,
                       SUM(CASE WHEN change_pct < 0 THEN 1 ELSE 0 END)      AS declining,
                       100.0 * SUM(CASE WHEN change_pct > 0 THEN 1 ELSE 0 END)
                             / COUNT(*)                                     AS advancing_pct,
                       AVG(change_pct)                                      AS mean_change_pct
                FROM v_daily_change
                GROUP BY session_date""");

        ddl.add("""
                CREATE VIEW v_symbol_beta AS
                SELECT c.symbol                                                        AS symbol,
                       COUNT(*)                                                        AS sessions,
                       (COUNT(*) * SUM(c.change_pct * m.mean_change_pct)
                          - SUM(c.change_pct) * SUM(m.mean_change_pct))
                       / NULLIF(COUNT(*) * SUM(m.mean_change_pct * m.mean_change_pct)
                          - SUM(m.mean_change_pct) * SUM(m.mean_change_pct), 0)        AS beta
                FROM v_daily_change c
                JOIN v_market_breadth m ON m.session_date = c.session_date
                GROUP BY c.symbol""");
        return ddl;
    }



    /**
     * v8: staging for the live session.
     *
     * <p>The monitor writes what it has seen so far here on every tick, so the
     * dashboards can follow a session in progress instead of showing whatever
     * snapshot happened to be taken last. This is deliberately a separate table
     * from {@code candle}: rows here may be provisional - the newest candle is
     * still forming and its high, low and close will change - and provisional
     * data must never become the canonical record.
     *
     * <p>Consolidation at the close re-fetches the authoritative day into
     * {@code candle} and empties the staging rows it supersedes.
     */
    private List<String> v8() {
        List<String> ddl = new ArrayList<>();
        ddl.add("""
                CREATE TABLE live_candle (
                  instrument_id    INTEGER NOT NULL REFERENCES instrument(id),
                  interval_minutes INTEGER NOT NULL,
                  ts_epoch         INTEGER NOT NULL,
                  session_date     TEXT NOT NULL,
                  open REAL, high REAL, low REAL, close REAL, volume INTEGER,
                  provisional      INTEGER NOT NULL DEFAULT 0,
                  updated_at       INTEGER NOT NULL,
                  PRIMARY KEY (instrument_id, interval_minutes, ts_epoch)
                ) WITHOUT ROWID""");
        ddl.add("CREATE INDEX ix_live_candle_session ON live_candle (session_date)");

        /*
         * What the intraday panel reads: the settled tape, plus anything the
         * monitor has staged that has not been consolidated yet. Canonical rows
         * always win, so a consolidated session never shows two versions of the
         * same minute. `source` distinguishes them, and a still-forming candle
         * is labelled rather than quietly presented as settled.
         */
        ddl.add("""
                CREATE VIEW v_intraday_merged AS
                SELECT symbol, ts_epoch, session_date, interval_minutes,
                       open, high, low, close, volume, 'final' AS source
                FROM v_intraday_candles
                UNION ALL
                SELECT i.symbol, l.ts_epoch, l.session_date, l.interval_minutes,
                       l.open, l.high, l.low, l.close, l.volume,
                       CASE WHEN l.provisional = 1 THEN 'forming' ELSE 'live' END AS source
                FROM live_candle l
                JOIN instrument i ON i.id = l.instrument_id
                WHERE NOT EXISTS (
                      SELECT 1 FROM candle c
                      WHERE c.instrument_id = l.instrument_id
                        AND c.interval_minutes = l.interval_minutes
                        AND c.ts_epoch = l.ts_epoch)""");
        return ddl;
    }



    /**
     * v9: carry VWAP on the intraday view.
     *
     * <p>Volume-weighted average price is a running total within one session -
     * cumulative (typical price x volume) over cumulative volume, restarting
     * each morning. It is the benchmark a fill gets judged against, and the
     * event detector already keys VWAP_RECLAIM and VWAP_LOSS off it, so the
     * chart should be able to show the same line those events refer to.
     *
     * <p>Computed here rather than in panel SQL so there is one definition, and
     * partitioned by session so yesterday's volume never leaks into today's
     * average.
     */
    private List<String> v9() {
        List<String> ddl = new ArrayList<>();
        ddl.add("DROP VIEW IF EXISTS v_intraday_merged");
        ddl.add("""
                CREATE VIEW v_intraday_merged AS
                SELECT symbol, ts_epoch, session_date, interval_minutes,
                       open, high, low, close, volume, source,
                       SUM(((high + low + close) / 3.0) * volume) OVER w
                         / NULLIF(SUM(volume) OVER w, 0) AS vwap
                FROM (
                  SELECT symbol, ts_epoch, session_date, interval_minutes,
                         open, high, low, close, volume, 'final' AS source
                  FROM v_intraday_candles
                  UNION ALL
                  SELECT i.symbol, l.ts_epoch, l.session_date, l.interval_minutes,
                         l.open, l.high, l.low, l.close, l.volume,
                         CASE WHEN l.provisional = 1 THEN 'forming' ELSE 'live' END
                  FROM live_candle l
                  JOIN instrument i ON i.id = l.instrument_id
                  WHERE NOT EXISTS (
                        SELECT 1 FROM candle c
                        WHERE c.instrument_id = l.instrument_id
                          AND c.interval_minutes = l.interval_minutes
                          AND c.ts_epoch = l.ts_epoch)
                )
                WINDOW w AS (PARTITION BY symbol, session_date ORDER BY ts_epoch
                             ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)""");
        return ddl;
    }



    /**
     * v10: the screening views, and somewhere to keep a real 52-week range.
     *
     * <p>The 52-week high and low deliberately live in their own table rather
     * than in {@code trading_day}. They come from daily-interval candles over a
     * year, and {@code trading_day} is keyed by interval - mixing a second
     * interval in would silently double every daily view, none of which filter
     * on it.
     */
    private List<String> v10() {
        List<String> ddl = new ArrayList<>();
        ddl.add("""
                CREATE TABLE symbol_week52 (
                  instrument_id INTEGER PRIMARY KEY REFERENCES instrument(id),
                  week52_high REAL,
                  week52_low  REAL,
                  sessions    INTEGER NOT NULL,
                  from_date   TEXT,
                  to_date     TEXT,
                  computed_at INTEGER NOT NULL
                )""");

        // Where price sits inside its own recent range, and how much value
        // actually changed hands. Only rows with a full ten-session window.
        ddl.add("""
                CREATE VIEW v_range_position AS
                SELECT symbol, session_date, ts_epoch, close,
                       high_10, low_10, turnover_10, avg_daily_turnover_10,
                       CASE WHEN high_10 > low_10
                            THEN (close - low_10) / (high_10 - low_10) * 100.0
                            ELSE NULL END AS pct_of_10d_range
                FROM (
                  SELECT i.symbol AS symbol, t.session_date AS session_date,
                         t.first_candle_ts AS ts_epoch, t.close AS close,
                         MAX(t.high) OVER w10            AS high_10,
                         MIN(t.low)  OVER w10            AS low_10,
                         SUM(t.close * t.volume) OVER w10 AS turnover_10,
                         AVG(t.close * t.volume) OVER w10 AS avg_daily_turnover_10,
                         COUNT(*) OVER w10               AS window_size
                  FROM trading_day t JOIN instrument i ON i.id = t.instrument_id
                  WINDOW w10 AS (PARTITION BY i.symbol ORDER BY t.session_date
                                 ROWS BETWEEN 9 PRECEDING AND CURRENT ROW)
                )
                WHERE window_size = 10""");

        /*
         * A ranking of how strong a stock's current setup looks, on the most
         * recent session. Every term is in percentage-ish units so they add
         * meaningfully:
         *
         *   distance above the 20-day average, in percent
         * + the last five sessions' return, in percent
         * + (RSI - 50) / 10, so RSI 70 contributes +2 and RSI 30 contributes -2
         * - an overbought penalty above RSI 75
         *
         * This describes momentum that already exists. It is not a forecast,
         * and nothing here knows anything about tomorrow.
         */
        ddl.add("""
                CREATE VIEW v_bullish_ranking AS
                SELECT symbol, session_date, ts_epoch, open, high, low, close, volume,
                       sma20, sma50, rsi14, trend_score, ret5, pct_above_sma20,
                       ROUND(pct_above_sma20 + ret5
                             + CASE WHEN rsi14 IS NULL THEN 0 ELSE (rsi14 - 50) / 10.0 END
                             - CASE WHEN rsi14 > 75 THEN (rsi14 - 75) / 5.0 ELSE 0 END, 3)
                       AS bullish_score
                FROM (
                  SELECT t.*,
                         CASE WHEN t.sma20 IS NULL OR t.sma20 = 0 THEN NULL
                              ELSE (t.close - t.sma20) / t.sma20 * 100.0 END AS pct_above_sma20,
                         CASE WHEN LAG(t.close, 5) OVER p IS NULL THEN NULL
                              ELSE (t.close - LAG(t.close, 5) OVER p)
                                   / LAG(t.close, 5) OVER p * 100.0 END      AS ret5,
                         ROW_NUMBER() OVER (PARTITION BY t.symbol
                                            ORDER BY t.session_date DESC)    AS recency
                  FROM v_symbol_trend t
                  WINDOW p AS (PARTITION BY t.symbol ORDER BY t.session_date)
                )
                WHERE recency = 1""");
        return ddl;
    }



    /**
     * v11: one row per symbol placing today's close in every range that matters.
     *
     * <p>Ten sessions says where a stock sits this fortnight; a year says
     * whether it is actually near a low. They answer different questions, so
     * both are carried side by side.
     *
     * <p>{@code week52_sessions} is exposed rather than hidden: a symbol with
     * only a few months of daily history has a "52-week" low that is really a
     * few-months low, and a screen should say so instead of implying a year.
     */
    private List<String> v11() {
        return List.of("""
                CREATE VIEW v_range_context AS
                WITH latest_close AS (
                  SELECT symbol, session_date, ts_epoch, close,
                         ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY session_date DESC) AS rn
                  FROM v_symbol_trend),
                latest_range AS (
                  SELECT symbol, high_10, low_10, pct_of_10d_range, avg_daily_turnover_10,
                         ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY session_date DESC) AS rn
                  FROM v_range_position)
                SELECT c.symbol                          AS symbol,
                       c.session_date                    AS session_date,
                       c.ts_epoch                        AS ts_epoch,
                       c.close                           AS close,
                       r.high_10, r.low_10, r.pct_of_10d_range, r.avg_daily_turnover_10,
                       w.week52_high, w.week52_low,
                       w.sessions                        AS week52_sessions,
                       CASE WHEN w.week52_high > w.week52_low
                            THEN (c.close - w.week52_low)
                                 / (w.week52_high - w.week52_low) * 100.0
                            ELSE NULL END                AS pct_of_52w_range
                FROM latest_close c
                LEFT JOIN latest_range r ON r.symbol = c.symbol AND r.rn = 1
                LEFT JOIN instrument i   ON i.symbol = c.symbol
                LEFT JOIN symbol_week52 w ON w.instrument_id = i.id
                WHERE c.rn = 1""");
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
