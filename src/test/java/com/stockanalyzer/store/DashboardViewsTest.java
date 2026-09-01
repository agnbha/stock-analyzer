package com.stockanalyzer.store;

import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.DailyGainSummary;
import com.stockanalyzer.model.GainOpportunity;
import com.stockanalyzer.model.Product;
import com.stockanalyzer.model.Side;
import com.stockanalyzer.model.Trade;
import com.stockanalyzer.store.jdbc.SqliteAccountBalanceRepository;
import com.stockanalyzer.store.jdbc.SqliteCandleRepository;
import com.stockanalyzer.store.jdbc.SqliteGainOpportunityRepository;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import com.stockanalyzer.store.jdbc.SqliteRealizedLotRepository;
import com.stockanalyzer.store.jdbc.SqliteTradeReasonRepository;
import com.stockanalyzer.store.jdbc.SqliteTradeRepository;
import com.stockanalyzer.store.jdbc.SqliteTradingDayRepository;
import com.stockanalyzer.trade.FifoLotMatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dashboards read these views, so a broken one has to fail here, not in Grafana. */
class DashboardViewsTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 24);
    private static final long NINE_FIFTEEN = 1787362500L;
    private static final String DETECTOR = "topk-nonoverlap/highlow/v1";

    @TempDir
    Path directory;

    private Database database;
    private TradeRepository trades;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        InstrumentRepository instruments = new SqliteInstrumentRepository(database);
        SqliteTradingDayRepository tradingDays = new SqliteTradingDayRepository(database);
        trades = new SqliteTradeRepository(database);

        long instrumentId = instruments.findOrCreate("RELIANCE", "NSE", "CASH");
        for (int day = 0; day < 5; day++) {
            LocalDate date = MONDAY.plusDays(day);
            tradingDays.upsert(instrumentId, new DailyGainSummary("RELIANCE", date, 1,
                    100 + day, 110 + day, 95 + day, 105 + day, 10_000, 1.0, 375,
                    NINE_FIFTEEN + day * 86400L, NINE_FIFTEEN + day * 86400L + 22500, List.of()), "test");
        }

        trades.importAll(List.of(
                trade("t1", Side.BUY, 10, 100, MONDAY, NINE_FIFTEEN + 600, 12.5),
                trade("t2", Side.SELL, 10, 108, MONDAY, NINE_FIFTEEN + 3600, 15.0),
                trade("t3", Side.BUY, 5, 200, MONDAY.plusDays(1), NINE_FIFTEEN + 86400, 8.0)),
                "NSE", "CASH");

        List<Trade> stored = trades.findAllOrdered();
        FifoLotMatcher.Result matched = new FifoLotMatcher().match(stored);
        new SqliteRealizedLotRepository(database).replaceAll(matched.lots(), matched.openPositions(),
                "NSE", "CASH");

        new SqliteTradeReasonRepository(database).upsertAll(List.of(
                new TradeReasonRepository.TradeReason(stored.getFirst().id(), "VWAP_RECLAIM",
                        TradeReasonRepository.TradeReason.Source.EVENT, "2 min before the fill"),
                new TradeReasonRepository.TradeReason(stored.get(1).id(), "TARGET_HIT",
                        TradeReasonRepository.TradeReason.Source.MANUAL, "took the money")));

        new SqliteAccountBalanceRepository(database).record(MONDAY, 250_000, 50_000.0, "manual");

        // A session at minute resolution, with a known top-3 window inside it.
        List<Candle> minutes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            double close = 100 + i;
            minutes.add(new Candle(NINE_FIFTEEN + i * 60L, close, close + 0.5, close - 0.5, close, 500));
        }
        new SqliteCandleRepository(database).saveAll(instrumentId, 1, minutes);

        long tradingDayId = tradingDays.findId(instrumentId, MONDAY, 1).orElseThrow();
        new SqliteGainOpportunityRepository(database).replace(tradingDayId, DETECTOR, List.of(
                new GainOpportunity(1, NINE_FIFTEEN + 60L, NINE_FIFTEEN + 5 * 60L, 100.5, 105.5, 4.98, 4),
                new GainOpportunity(2, NINE_FIFTEEN + 7 * 60L, NINE_FIFTEEN + 9 * 60L, 106.5, 109.5, 2.82, 2)));
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private static Trade trade(String id, Side side, int quantity, double price, LocalDate date,
                               long executedTs, double charges) {
        String json = "{\"brokerage\":5.00,\"stt\":4.00,\"exchange\":1.00,\"sebi\":0.50,"
                + "\"stamp\":1.00,\"gst\":1.00}";
        return new Trade(0, "RELIANCE", id, null, date, executedTs, side, quantity, price, Product.MIS,
                charges, json, Trade.ChargesSource.MODELLED, Trade.TradeSource.MANUAL, null);
    }

    private double queryDouble(String sql) {
        return database.read(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(sql)) {
                return rs.next() ? rs.getDouble(1) : Double.NaN;
            }
        });
    }

    private int queryInt(String sql) {
        return (int) queryDouble(sql);
    }

    @Test
    @DisplayName("bet amount is quantity times price, with taxes split out from brokerage")
    void betAmountsAndCharges() {
        assertEquals(3, queryInt("SELECT COUNT(*) FROM v_bet"));
        assertEquals(1000.0, queryDouble("SELECT bet_amount FROM v_bet WHERE trade_id = 1"), 0.01);
        assertEquals(6.0, queryDouble("SELECT taxes FROM v_bet WHERE trade_id = 1"), 0.01,
                "STT plus stamp duty plus GST");
        assertEquals(5.0, queryDouble("SELECT brokerage FROM v_bet WHERE trade_id = 1"), 0.01);
    }

    @Test
    @DisplayName("daily P&L joins the day's fills to the lots closed that day")
    void dailyPnl() {
        assertEquals(2, queryInt("SELECT COUNT(*) FROM v_daily_pnl"), "two days had trades");
        assertEquals(2080.0, queryDouble(
                "SELECT bet_amount FROM v_daily_pnl WHERE session_date = '2026-08-24'"), 0.01);
        assertEquals(80.0, queryDouble(
                "SELECT gross_pnl FROM v_daily_pnl WHERE session_date = '2026-08-24'"), 0.01,
                "bought at 100, sold at 108, ten shares");
        assertEquals(1, queryInt("SELECT closed_lots FROM v_daily_pnl WHERE session_date = '2026-08-24'"));
    }

    @Test
    @DisplayName("the equity curve carries the recorded cash balance forward")
    void equityCurve() {
        assertEquals(250_000.0, queryDouble(
                "SELECT recorded_cash FROM v_equity_curve WHERE session_date = '2026-08-25'"), 0.01);
        assertTrue(queryDouble("SELECT realized_net_cumulative FROM v_equity_curve "
                + "WHERE session_date = '2026-08-25'") != 0.0, "cumulative P&L accumulates across days");
    }

    @Test
    @DisplayName("weekly candles open on the week's first session and close on its last")
    void weeklyCandles() {
        assertEquals(5, queryInt("SELECT COUNT(*) FROM v_daily_candles"));
        assertEquals(1, queryInt("SELECT COUNT(*) FROM v_weekly_candles"), "one trading week");
        assertEquals(100.0, queryDouble("SELECT open FROM v_weekly_candles"), 0.01, "Monday's open");
        assertEquals(109.0, queryDouble("SELECT close FROM v_weekly_candles"), 0.01, "Friday's close");
        assertEquals(114.0, queryDouble("SELECT high FROM v_weekly_candles"), 0.01);
        assertEquals(95.0, queryDouble("SELECT low FROM v_weekly_candles"), 0.01);
        assertEquals(50_000.0, queryDouble("SELECT volume FROM v_weekly_candles"), 0.01);
    }

    @Test
    @DisplayName("trade markers carry the side and price the dashboards plot")
    void tradeMarkers() {
        assertEquals(3, queryInt("SELECT COUNT(*) FROM v_trade_markers"));
        assertEquals(2, queryInt("SELECT COUNT(*) FROM v_trade_markers WHERE side = 'BUY'"));
    }

    @Test
    @DisplayName("minute candles carry the day's top-3 windows as entry and exit markers")
    void intradayCandlesCarryOpportunityMarkers() {
        assertEquals(10, queryInt("SELECT COUNT(*) FROM v_intraday_candles WHERE symbol='RELIANCE'"));
        assertEquals(2, queryInt("SELECT COUNT(*) FROM v_opportunity_markers WHERE symbol='RELIANCE'"));

        // The join the intraday panel makes: a candle row gains an entry price
        // on the minute a window opens, and an exit price on the minute it closes.
        String panelJoin = """
                SELECT COUNT(*) FROM v_intraday_candles c
                LEFT JOIN v_opportunity_markers e
                       ON e.symbol = c.symbol AND e.entry_ts = c.ts_epoch
                LEFT JOIN v_opportunity_markers x
                       ON x.symbol = c.symbol AND x.exit_ts = c.ts_epoch
                WHERE c.symbol = 'RELIANCE' AND (e.entry_price IS NOT NULL OR x.exit_price IS NOT NULL)""";
        assertEquals(4, queryInt(panelJoin), "two windows, each contributing an entry and an exit");

        assertEquals(100.5, queryDouble("SELECT e.entry_price FROM v_intraday_candles c "
                + "JOIN v_opportunity_markers e ON e.symbol=c.symbol AND e.entry_ts=c.ts_epoch "
                + "WHERE e.rank = 1"), 0.001);
    }

    @Test
    @DisplayName("markers stay separated by detector version")
    void markersAreKeyedByDetectorVersion() {
        assertEquals(2, queryInt("SELECT COUNT(*) FROM v_opportunity_markers "
                + "WHERE detector_version = '" + DETECTOR + "'"));
        assertEquals(0, queryInt("SELECT COUNT(*) FROM v_opportunity_markers "
                + "WHERE detector_version = 'some-other/v9'"));
    }

    @Test
    @DisplayName("reason frequency counts occurrences and attaches the P&L that followed")
    void reasonFrequency() {
        assertEquals(2, queryInt("SELECT COUNT(*) FROM v_reason_frequency"));
        assertEquals(1, queryInt(
                "SELECT occurrences FROM v_reason_frequency WHERE reason_code = 'VWAP_RECLAIM'"));
        assertTrue(queryDouble("SELECT net_pnl FROM v_reason_frequency WHERE reason_code = 'TARGET_HIT'") > 0,
                "the sell that closed the winning lot carries its net P&L");
    }
}
