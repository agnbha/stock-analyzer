package com.stockanalyzer.trade;

import com.stockanalyzer.model.Product;
import com.stockanalyzer.model.Side;
import com.stockanalyzer.model.Trade;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.TestDatabase;
import com.stockanalyzer.store.TradeReasonRepository.TradeReason;
import com.stockanalyzer.store.jdbc.SqliteAlertRepository;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import com.stockanalyzer.store.jdbc.SqliteMarketEventRepository;
import com.stockanalyzer.store.jdbc.SqliteTradeNoteRepository;
import com.stockanalyzer.store.jdbc.SqliteTradeRepository;
import com.stockanalyzer.util.MarketClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A sentiment you recorded is the reason a call was taken: it is counted, and its P&L can be measured. */
class TradeNoteAttributionTest {

    private static final MarketClock CLOCK = MarketClock.nse();
    private static final LocalDate DAY = LocalDate.of(2026, 11, 4);

    @TempDir
    Path directory;

    private Database database;
    private SqliteTradeRepository trades;
    private TradeNoteService notes;
    private ReasonAttributor attributor;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        trades = new SqliteTradeRepository(database);
        SqliteTradeNoteRepository noteRepository = new SqliteTradeNoteRepository(database);
        notes = new TradeNoteService(trades, noteRepository, CLOCK);
        attributor = new ReasonAttributor(new SqliteMarketEventRepository(database), new SqliteAlertRepository(database),
                noteRepository, new SqliteInstrumentRepository(database), "NSE", "CASH", 15);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private void fill(String symbol, String id, int hour, int minute) {
        trades.importAll(List.of(new Trade(0, symbol, id, "o-" + id, DAY, CLOCK.epochOf(DAY, LocalTime.of(hour, minute)),
                Side.BUY, 10, 100.0, Product.MIS, 0, null, Trade.ChargesSource.MODELLED, Trade.TradeSource.BROKER,
                null)), "NSE", "CASH");
    }

    private List<TradeReason> reasonsFor(String symbol) {
        List<Trade> forSymbol = trades.findRange(DAY, DAY).stream().filter(t -> t.symbol().equals(symbol)).toList();
        return attributor.attribute(forSymbol);
    }

    @Test
    @DisplayName("the sentiment becomes the reason, with the words as its detail")
    void sentimentIsTheReason() {
        fill("RELIANCE", "t1", 9, 28);
        notes.note(new TradeNoteService.Selection("RELIANCE", Side.BUY, DAY, null, null), "bullish",
                "Results beat, sector strong.", 1);

        List<TradeReason> reasons = reasonsFor("RELIANCE");

        assertEquals(1, reasons.size());
        assertEquals("BULLISH", reasons.getFirst().reasonCode());
        assertEquals(TradeReason.Source.MANUAL, reasons.getFirst().source());
        assertEquals("Results beat, sector strong.", reasons.getFirst().detail());
    }

    @Test
    @DisplayName("words with no sentiment are counted by what they say, as a manual note is")
    void textAloneIsTheReason() {
        fill("RELIANCE", "t1", 9, 28);
        notes.note(new TradeNoteService.Selection("RELIANCE", Side.BUY, DAY, null, null), null, "VWAP reclaim", 1);

        assertEquals("VWAP_RECLAIM", reasonsFor("RELIANCE").getFirst().reasonCode());
    }

    @Test
    @DisplayName("a fill with no note is still UNEXPLAINED, and another symbol's note does not leak onto it")
    void unnotedFillsAreUnchanged() {
        fill("RELIANCE", "t1", 9, 28);
        fill("TCS", "t2", 9, 28);
        notes.note(new TradeNoteService.Selection("RELIANCE", Side.BUY, DAY, null, null), "BULLISH", null, 1);

        List<TradeReason> tcs = reasonsFor("TCS");

        assertEquals(1, tcs.size());
        assertEquals(TradeReason.Source.UNEXPLAINED, tcs.getFirst().source());
        assertTrue(reasonsFor("RELIANCE").stream().anyMatch(r -> r.reasonCode().equals("BULLISH")));
    }
}
