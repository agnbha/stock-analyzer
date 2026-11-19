package com.stockanalyzer.markets;

import com.stockanalyzer.canslim.GrowwInstrumentCatalog.FutureContract;
import com.stockanalyzer.client.CandleDataClient;
import com.stockanalyzer.client.GrowwApiException;
import com.stockanalyzer.client.GrowwSymbolRejectedException;
import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.StockCandleSeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which MCX contract's close stands for a commodity's price each day. The prices and volumes are invented. */
class McxFuturesClientTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** 19 Nov 2026, 18:30 in India: the evening run, while MCX is still trading. */
    private static final Clock EVENING = Clock.fixed(Instant.parse("2026-11-19T13:00:00Z"), ZoneId.of("UTC"));
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);

    private static final FutureContract NOV = new FutureContract("CRUDEOIL19NOV26FUT", "CRUDEOIL",
            LocalDate.of(2026, 11, 19), 100);
    private static final FutureContract DEC = new FutureContract("CRUDEOIL18DEC26FUT", "CRUDEOIL",
            LocalDate.of(2026, 12, 18), 100);
    private static final FutureContract JAN = new FutureContract("CRUDEOIL19JAN27FUT", "CRUDEOIL",
            LocalDate.of(2027, 1, 19), 100);
    private static final FutureContract FEB = new FutureContract("CRUDEOIL19FEB27FUT", "CRUDEOIL",
            LocalDate.of(2027, 2, 19), 100);

    /** A daily candle as Groww stamps it, at midnight in India; volume in barrels, 100 to a lot. */
    private static Candle day(String date, double close, long volume) {
        long midnight = LocalDate.parse(date).atStartOfDay(IST).toEpochSecond();
        return new Candle(midnight, close, close, close, close, volume);
    }

    private static Observation on(String date, double value) {
        return new Observation(LocalDate.parse(date), value);
    }

    private static List<Observation> mostTraded(Map<FutureContract, List<Candle>> read) {
        return McxFuturesClient.mostTraded(read, TODAY, IST);
    }

    @Test
    @DisplayName("each day's price is the close of the contract traded most that day")
    void theMostTradedContractsClose() {
        Map<FutureContract, List<Candle>> read = new LinkedHashMap<>();
        read.put(NOV, List.of(day("2026-11-16", 7000, 500_000), day("2026-11-17", 7010, 400_000),
                day("2026-11-18", 6990, 200_000)));
        read.put(DEC, List.of(day("2026-11-16", 6950, 100_000), day("2026-11-17", 6960, 300_000),
                day("2026-11-18", 6940, 260_000)));

        assertEquals(List.of(on("2026-11-16", 7000), on("2026-11-17", 7010), on("2026-11-18", 6940)),
                mostTraded(read), "December out-traded November on the 18th");
    }

    @Test
    @DisplayName("once the next contract has been the most traded, the price does not go back to the expiring one")
    void doesNotRollBack() {
        Map<FutureContract, List<Candle>> read = new LinkedHashMap<>();
        read.put(NOV, List.of(day("2026-11-12", 7000, 500_000), day("2026-11-13", 7010, 200_000),
                day("2026-11-16", 7020, 400_000)));
        read.put(DEC, List.of(day("2026-11-12", 6950, 100_000), day("2026-11-13", 6960, 300_000),
                day("2026-11-16", 6970, 300_000)));

        assertEquals(List.of(on("2026-11-12", 7000), on("2026-11-13", 6960), on("2026-11-16", 6970)),
                mostTraded(read), "the 16th's late burst in November is its holders squaring up, not the market");
    }

    @Test
    @DisplayName("today is left out until it is over, and so is a day on which no contract traded ten lots")
    void leavesOutTodayAndThinDays() {
        Map<FutureContract, List<Candle>> read = new LinkedHashMap<>();
        read.put(DEC, List.of(day("2026-06-01", 6000, 300), day("2026-06-02", 6010, 1_000),
                day("2026-11-18", 6940, 260_000), day("2026-11-19", 6900, 90_000)));
        read.put(JAN, List.of(day("2026-06-01", 6100, 900), day("2026-11-19", 6880, 120_000)));

        assertEquals(List.of(on("2026-06-02", 6010), on("2026-11-18", 6940)), mostTraded(read),
                "1 June: 3 and 9 lots; 19 Nov: still trading");
    }

    @Test
    @DisplayName("a thin day does not move the price to a far contract for good")
    void aThinDayDoesNotRoll() {
        Map<FutureContract, List<Candle>> read = new LinkedHashMap<>();
        read.put(DEC, List.of(day("2026-07-01", 6000, 500), day("2026-07-02", 6010, 50_000)));
        read.put(JAN, List.of(day("2026-07-01", 6100, 900), day("2026-07-02", 6120, 1_000)));

        assertEquals(List.of(on("2026-07-02", 6010)), mostTraded(read));
    }

    /** Candles by contract, and the requests made of them. */
    private static final class FakeCandles implements CandleDataClient {
        final Map<String, List<Candle>> candles = new HashMap<>();
        final List<String> asked = new ArrayList<>();
        final Map<String, RuntimeException> failures = new HashMap<>();

        @Override
        public StockCandleSeries fetchCandles(String symbol, String exchange, String segment, LocalDateTime start,
                                              LocalDateTime end, int interval) {
            asked.add(symbol + " " + exchange + " " + segment + " " + start.toLocalDate() + ".." + end.toLocalDate()
                    + " " + interval);
            if (failures.containsKey(symbol)) {
                throw failures.get(symbol);
            }
            return new StockCandleSeries(symbol, exchange, segment, candles.getOrDefault(symbol, List.of()));
        }
    }

    private static McxFuturesClient client(List<FutureContract> listed, CandleDataClient candles) {
        return new McxFuturesClient(underlying -> listed, candles, EVENING, IST);
    }

    @Test
    @DisplayName("reads the daily candles of the three nearest contracts still trading, from the date asked")
    void readsTheNearestThree() {
        FakeCandles candles = new FakeCandles();
        candles.candles.put(NOV.symbol(), List.of(day("2026-11-17", 7010, 400_000), day("2026-11-18", 6990, 200_000)));
        candles.candles.put(DEC.symbol(), List.of(day("2026-11-17", 6960, 300_000), day("2026-11-18", 6940, 260_000)));
        FutureContract expired = new FutureContract("CRUDEOIL19OCT26FUT", "CRUDEOIL", LocalDate.of(2026, 10, 19), 100);

        List<Observation> prices = client(List.of(expired, NOV, DEC, JAN, FEB), candles)
                .fetch("CRUDEOIL", LocalDate.of(2026, 11, 18));

        assertEquals(List.of("CRUDEOIL19NOV26FUT MCX COMMODITY 2026-11-18..2026-11-19 1440",
                "CRUDEOIL18DEC26FUT MCX COMMODITY 2026-11-18..2026-11-19 1440",
                "CRUDEOIL19JAN27FUT MCX COMMODITY 2026-11-18..2026-11-19 1440"), candles.asked,
                "November expires today and still counts; October has gone, February is too far out");
        assertEquals(List.of(on("2026-11-18", 6940)), prices, "only from the date asked");
    }

    @Test
    @DisplayName("a contract Groww no longer knows is passed over; any other failure is the series' failure")
    void failures() {
        FakeCandles candles = new FakeCandles();
        candles.failures.put(NOV.symbol(), new GrowwSymbolRejectedException("unknown trading symbol", 400));
        candles.candles.put(DEC.symbol(), List.of(day("2026-11-18", 6940, 260_000)));

        assertEquals(List.of(on("2026-11-18", 6940)),
                client(List.of(NOV, DEC), candles).fetch("CRUDEOIL", LocalDate.of(2026, 11, 1)));

        candles.failures.put(DEC.symbol(), new GrowwApiException("Groww candle request failed with status 503", 503));
        MarketDataException e = assertThrows(MarketDataException.class,
                () -> client(List.of(NOV, DEC), candles).fetch("CRUDEOIL", LocalDate.of(2026, 11, 1)));
        assertTrue(e.getMessage().contains("CRUDEOIL18DEC26FUT"), e.getMessage());

        MarketDataException none = assertThrows(MarketDataException.class,
                () -> client(List.of(), candles).fetch("CRUDEOIL", LocalDate.of(2026, 11, 1)));
        assertTrue(none.getMessage().contains("no MCX CRUDEOIL future"), none.getMessage());
    }
}
