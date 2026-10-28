package com.stockanalyzer.trade;

import com.stockanalyzer.model.Product;
import com.stockanalyzer.model.Side;
import com.stockanalyzer.model.Trade;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.InstrumentRepository;
import com.stockanalyzer.store.TestDatabase;
import com.stockanalyzer.store.jdbc.SqliteAlertRepository;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import com.stockanalyzer.store.jdbc.SqliteMarketEventRepository;
import com.stockanalyzer.store.jdbc.SqlitePnlPeriodRepository;
import com.stockanalyzer.store.jdbc.SqliteRealizedLotRepository;
import com.stockanalyzer.store.jdbc.SqliteTradeReasonRepository;
import com.stockanalyzer.store.jdbc.SqliteTradeRepository;
import com.stockanalyzer.store.jdbc.SqliteTradingDayRepository;
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

/** What the daemon relies on: a fill that arrives mid-session ends up on the dashboard's marker view. */
class BrokerFillSyncTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 28);
    private static final long TEN_FIFTEEN = 1_793_162_723L;
    private static final long ELEVEN_NINE = TEN_FIFTEEN + 3_238L;

    @TempDir
    Path directory;

    private Database database;
    private BrokerFillSync sync;
    private final List<Trade> brokerBook = new ArrayList<>();
    private final ExecutionDataClient broker = (from, to) -> List.copyOf(brokerBook);

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        InstrumentRepository instruments = new SqliteInstrumentRepository(database);
        SqliteTradeRepository trades = new SqliteTradeRepository(database);
        SqliteTradeReasonRepository reasons = new SqliteTradeReasonRepository(database);
        TradeJournalService journal = new TradeJournalService(trades, new SqliteRealizedLotRepository(database),
                new SqlitePnlPeriodRepository(database), instruments, new SqliteTradingDayRepository(database),
                new FifoLotMatcher(), new GrowwChargeModel(ChargeRates.defaults()), new PeriodAggregator(),
                new PeriodBounds(4), false, "NSE", "CASH");
        ReasonAttributor attributor = new ReasonAttributor(new SqliteMarketEventRepository(database),
                new SqliteAlertRepository(database), instruments, "NSE", "CASH", 15);
        sync = new BrokerFillSync(journal, trades, reasons, attributor);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private static Trade fill(String id, Side side, int quantity, double price, long at) {
        return new Trade(0, "BHARTIARTL", id, "order-" + id, DAY, at, side, quantity, price, Product.CNC,
                0, null, Trade.ChargesSource.MODELLED, Trade.TradeSource.BROKER, null);
    }

    private List<String> markers() {
        return database.read(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT side || ' ' || quantity || ' @ ' || price FROM v_trade_markers "
                                 + "WHERE symbol = 'BHARTIARTL' ORDER BY ts_epoch")) {
                List<String> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(rs.getString(1));
                }
                return rows;
            }
        });
    }

    @Test
    @DisplayName("a sell that appears after the first sync reaches the chart markers on the next one")
    void laterFillsAppear() {
        brokerBook.add(fill("b1", Side.BUY, 257, 1938.1, TEN_FIFTEEN));
        BrokerFillSync.Result first = sync.sync(broker, DAY, DAY);

        assertEquals(1, first.newTrades());
        assertEquals(List.of("BUY 257 @ 1938.1"), markers());
        assertEquals(1, first.openPositions());

        brokerBook.add(fill("s1", Side.SELL, 257, 1946.0, ELEVEN_NINE));
        BrokerFillSync.Result second = sync.sync(broker, DAY, DAY);

        assertEquals(1, second.newTrades(), "only the sell is new");
        assertEquals(List.of("BUY 257 @ 1938.1", "SELL 257 @ 1946.0"), markers());
        assertEquals(1, second.closedLots());
        assertEquals(0, second.openPositions(), "the position is closed again");
    }

    @Test
    @DisplayName("syncing again with nothing new changes nothing")
    void idempotent() {
        brokerBook.add(fill("b1", Side.BUY, 100, 1938.1, TEN_FIFTEEN));
        brokerBook.add(fill("s1", Side.SELL, 100, 1946.0, ELEVEN_NINE));
        sync.sync(broker, DAY, DAY);

        BrokerFillSync.Result again = sync.sync(broker, DAY, DAY);

        assertEquals(0, again.newTrades());
        assertEquals(2, markers().size());
        assertEquals(1, again.closedLots());
    }
}
