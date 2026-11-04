package com.stockanalyzer.trade;

import com.stockanalyzer.model.Product;
import com.stockanalyzer.model.Side;
import com.stockanalyzer.model.Trade;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.TestDatabase;
import com.stockanalyzer.store.TradeNoteRepository;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeNoteServiceTest {

    private static final MarketClock CLOCK = MarketClock.nse();
    private static final LocalDate DAY = LocalDate.of(2026, 11, 4);
    private static final String SYMBOL = "BHARTIARTL";

    @TempDir
    Path directory;

    private Database database;
    private SqliteTradeRepository trades;
    private SqliteTradeNoteRepository noteRepository;
    private TradeNoteService service;
    private int sequence;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        trades = new SqliteTradeRepository(database);
        noteRepository = new SqliteTradeNoteRepository(database);
        service = new TradeNoteService(trades, noteRepository, CLOCK);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private static long at(int hour, int minute, int second) {
        return CLOCK.epochOf(DAY, LocalTime.of(hour, minute)) + second;
    }

    /** One order's fills: the same second, slightly different prices, as the broker reports them. */
    private void order(String symbol, String orderId, Side side, long executedAt, int... quantitiesAndPence) {
        List<Trade> fills = new ArrayList<>();
        for (int i = 0; i < quantitiesAndPence.length; i += 2) {
            fills.add(new Trade(0, symbol, orderId + "-" + sequence++, orderId, DAY, executedAt, side,
                    quantitiesAndPence[i], quantitiesAndPence[i + 1] / 100.0, Product.MIS, 0, null,
                    Trade.ChargesSource.MODELLED, Trade.TradeSource.BROKER, null));
        }
        trades.importAll(fills, "NSE", "CASH");
    }

    private static TradeNoteService.Selection select(Side side, LocalTime at, String orderId) {
        return new TradeNoteService.Selection(SYMBOL, side, DAY, at, orderId);
    }

    private List<TradeNoteRepository.Noted> noted() {
        return noteRepository.findBetween(DAY, DAY, null);
    }

    @Test
    @DisplayName("a note is written against every fill of the call, and only that side of it")
    void noteCoversTheWholeCall() {
        order(SYMBOL, "o1", Side.BUY, at(9, 28, 19), 500, 206200, 5, 206200, 495, 206100);
        order(SYMBOL, "o2", Side.SELL, at(10, 40, 0), 400, 207000);

        TradeNoteService.Result result = service.note(select(Side.BUY, null, null), "bullish",
                "Results beat; sector strong.", 1_000);

        assertEquals(3, result.fills().size());
        assertEquals(1, result.calls().size());
        assertEquals(1000, result.calls().getFirst().quantity());
        assertEquals(2061.505, result.calls().getFirst().averagePrice(), 0.0001,
                "weighted by quantity: (500x2062 + 5x2062 + 495x2061) / 1000");
        assertEquals(3, noted().size());
        assertTrue(noted().stream().allMatch(n -> n.side() == Side.BUY && "BULLISH".equals(n.sentiment())
                && "Results beat; sector strong.".equals(n.note())));
    }

    @Test
    @DisplayName("with two purchases in a day, a note goes to both unless it is narrowed by time or order")
    void narrowingByTimeOrOrder() {
        order(SYMBOL, "morning", Side.BUY, at(9, 28, 19), 500, 206200);
        order(SYMBOL, "later", Side.BUY, at(9, 45, 56), 500, 205000);

        assertEquals(2, service.note(select(Side.BUY, null, null), "BULLISH", null, 1).calls().size());
        assertEquals(1, service.note(select(Side.BUY, LocalTime.of(9, 28), null), "NERVOUS", null, 2).calls().size());
        assertEquals(1, service.note(select(Side.BUY, null, "later"), "CALM", null, 3).calls().size());

        List<TradeNoteRepository.Noted> notes = noted();
        assertEquals("NERVOUS", notes.get(0).sentiment(), "the 09:28 fill was re-noted");
        assertEquals("CALM", notes.get(1).sentiment(), "and so was the order named later");
    }

    @Test
    @DisplayName("the time you give matches fills within three minutes of it, not further")
    void timeWindow() {
        order(SYMBOL, "o1", Side.BUY, at(9, 28, 19), 10, 206200);

        assertEquals(1, service.note(select(Side.BUY, LocalTime.of(9, 31), null), "BULLISH", null, 1).fills().size());
        assertThrows(IllegalArgumentException.class,
                () -> service.note(select(Side.BUY, LocalTime.of(9, 32), null), "BULLISH", null, 1));
    }

    @Test
    @DisplayName("asking for fills that are not there says what the day did contain")
    void nothingFoundExplainsWhatIsThere() {
        order(SYMBOL, "o1", Side.BUY, at(9, 28, 19), 500, 206200);

        IllegalArgumentException wrongSide = assertThrows(IllegalArgumentException.class,
                () -> service.note(select(Side.SELL, null, null), "BEARISH", null, 1));
        assertTrue(wrongSide.getMessage().contains("No SELL fills for BHARTIARTL on 2026-11-04"), wrongSide.getMessage());
        assertTrue(wrongSide.getMessage().contains("BUY") && wrongSide.getMessage().contains("500 shares at 2062.00"),
                wrongSide.getMessage());

        IllegalArgumentException wrongSymbol = assertThrows(IllegalArgumentException.class, () -> service.note(
                new TradeNoteService.Selection("TCS", Side.BUY, DAY, null, null), "BULLISH", null, 1));
        assertTrue(wrongSymbol.getMessage().contains("no fills for that symbol that day"), wrongSymbol.getMessage());
    }

    @Test
    @DisplayName("a note needs a sentiment, some words, or both")
    void needsSomethingToSay() {
        order(SYMBOL, "o1", Side.BUY, at(9, 28, 19), 500, 206200);

        assertThrows(IllegalArgumentException.class, () -> service.note(select(Side.BUY, null, null), " ", "  ", 1));
    }

    @Test
    @DisplayName("sentiments are normalised, so Bullish, BULLISH and ' bullish ' count as one")
    void sentimentIsNormalised() {
        assertEquals("BULLISH", TradeNoteService.normaliseSentiment(" Bullish "));
        assertEquals("VERY_NERVOUS", TradeNoteService.normaliseSentiment("very nervous!"));
        assertEquals(null, TradeNoteService.normaliseSentiment("  "));
        assertEquals(null, TradeNoteService.normaliseSentiment(null));
    }

    @Test
    @DisplayName("a sentiment you type in your own words is counted as the generic one: UP is BULLISH")
    void ownWordsAreCountedAsGenericSentiments() {
        order(SYMBOL, "o1", Side.BUY, at(9, 28, 19), 500, 206200);
        order(SYMBOL, "o2", Side.SELL, at(10, 40, 0), 500, 207000);

        service.note(select(Side.BUY, null, null), "up", "Should go up", 1);
        service.note(select(Side.SELL, null, null), "nervous", null, 2);

        List<TradeNoteRepository.Noted> notes = noted();
        assertEquals("BULLISH", notes.get(0).sentiment());
        assertEquals("NERVOUS", notes.get(1).sentiment(), "a label outside the vocabulary is kept as itself");
    }

    @Test
    @DisplayName("noting a call again replaces the note, and clearing removes it")
    void replaceAndClear() {
        order(SYMBOL, "o1", Side.BUY, at(9, 28, 19), 500, 206200);
        service.note(select(Side.BUY, null, null), "BULLISH", "first thought", 1);

        service.note(select(Side.BUY, null, null), "BEARISH", "second thought", 2);
        assertEquals(1, noted().size());
        assertEquals("BEARISH", noted().getFirst().sentiment());
        assertEquals("second thought", noted().getFirst().note());

        service.clear(select(Side.BUY, null, null));
        assertTrue(noted().isEmpty());
    }

    @Test
    @DisplayName("noted fills are folded back into calls when listed")
    void listGroupsFillsIntoCalls() {
        order(SYMBOL, "o1", Side.BUY, at(9, 28, 19), 100, 206000, 300, 206400);
        order("TCS", "o9", Side.BUY, at(9, 30, 0), 50, 250000);
        service.note(select(Side.BUY, null, null), "BULLISH", "momentum", 1);

        List<TradeNoteService.Entry> entries = service.list(DAY, DAY, null);

        assertEquals(1, entries.size());
        TradeNoteService.Entry entry = entries.getFirst();
        assertEquals(SYMBOL, entry.symbol());
        assertEquals(400, entry.quantity());
        assertEquals(2063.0, entry.averagePrice(), 0.0001, "(100x2060 + 300x2064) / 400");
        assertEquals(2, entry.fills());
        assertTrue(service.list(DAY, DAY, "TCS").isEmpty(), "TCS was never noted");
    }

    @Test
    @DisplayName("a note survives importing the same fills again")
    void survivesReimport() {
        order(SYMBOL, "o1", Side.BUY, at(9, 28, 19), 500, 206200);
        service.note(select(Side.BUY, null, null), "BULLISH", "momentum", 1);

        sequence = 0; // the same broker trade ids again, as a re-import brings them
        order(SYMBOL, "o1", Side.BUY, at(9, 28, 19), 500, 206200);

        assertEquals(1, noted().size());
        assertEquals("BULLISH", noted().getFirst().sentiment());
    }
}
