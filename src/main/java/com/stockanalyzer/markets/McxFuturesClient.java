package com.stockanalyzer.markets;

import com.stockanalyzer.canslim.GrowwInstrumentCatalog.FutureContract;
import com.stockanalyzer.client.CandleDataClient;
import com.stockanalyzer.client.GrowwSymbolRejectedException;
import com.stockanalyzer.model.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * An MCX commodity's daily closing price, in rupees as MCX quotes it, from Groww's daily candles of its futures: one
 * price a day, from whichever contract was the most traded that day, as the price a commodity "is" on the exchange.
 *
 * <p>Groww's candles cover only contracts still trading, back to when each was listed, so a commodity's history starts
 * where the oldest of them does and grows from there; the readings stored before a contract expired are kept. A day
 * is read once it is over: MCX trades until late at night, and Groww's candle for the day still trading is not the
 * day's.
 */
public final class McxFuturesClient {

    private static final Logger log = LoggerFactory.getLogger(McxFuturesClient.class);
    /** The front contract and the two after it: the most traded is always one of them. */
    static final int CONTRACTS = 3;
    /** A day on which no contract traded this many lots is left out: its price is a trade or two, not the market's. */
    static final int MIN_LOTS = 10;

    private final Function<String, List<FutureContract>> contracts;
    private final CandleDataClient candles;
    private final Clock clock;
    private final ZoneId zone;

    /**
     * @param contracts the futures on an underlying still trading, nearest expiry first (Groww's instrument list)
     * @param zone      the exchange's time zone, in which a candle's day is told
     */
    public McxFuturesClient(Function<String, List<FutureContract>> contracts, CandleDataClient candles, Clock clock,
                            ZoneId zone) {
        this.contracts = contracts;
        this.candles = candles;
        this.clock = clock;
        this.zone = zone;
    }

    /**
     * The commodity's closing prices from {@code from} up to yesterday, oldest first.
     *
     * @param underlying MCX's name for it: CRUDEOIL, GOLD, SILVER
     */
    public List<Observation> fetch(String underlying, LocalDate from) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        List<FutureContract> listed;
        try {
            listed = contracts.apply(underlying).stream().filter(c -> !c.expiry().isBefore(today))
                    .limit(CONTRACTS).toList();
        } catch (RuntimeException e) {
            throw new MarketDataException("could not read Groww's instrument list for " + underlying + ": "
                    + e.getMessage(), e);
        }
        if (listed.isEmpty()) {
            throw new MarketDataException("Groww's instrument list has no MCX " + underlying + " future still trading",
                    null);
        }
        Map<FutureContract, List<Candle>> read = new LinkedHashMap<>();
        for (FutureContract contract : listed) {
            try {
                read.put(contract, candles.fetchCandles(contract.symbol(), "MCX", "COMMODITY", from.atStartOfDay(),
                        today.atStartOfDay(), 1440).candles());
            } catch (GrowwSymbolRejectedException e) {
                // An instrument list a day old can still name a contract that expired since.
                log.info("Groww does not know {} any more; read the {} price without it", contract.symbol(),
                        underlying);
            } catch (RuntimeException e) {
                throw new MarketDataException("could not read Groww's candles for " + contract.symbol() + ": "
                        + e.getMessage(), e);
            }
        }
        return mostTraded(read, today, zone).stream().filter(o -> !o.date().isBefore(from)).toList();
    }

    /**
     * Each day before {@code today}, the close of the contract traded most that day. Once a later contract has been the
     * most traded, an earlier one is not gone back to, so a day of thin trading in the expiring contract does not jump
     * the price back to it.
     *
     * @param read each contract's daily candles, nearest expiry first
     */
    static List<Observation> mostTraded(Map<FutureContract, List<Candle>> read, LocalDate today, ZoneId zone) {
        List<FutureContract> order = new ArrayList<>(read.keySet());
        List<Map<LocalDate, Candle>> byDay = new ArrayList<>();
        TreeSet<LocalDate> days = new TreeSet<>();
        for (FutureContract contract : order) {
            Map<LocalDate, Candle> day = new TreeMap<>();
            for (Candle candle : read.get(contract)) {
                LocalDate date = Instant.ofEpochSecond(candle.epochSeconds()).atZone(zone).toLocalDate();
                if (date.isBefore(today)) {
                    day.put(date, candle);
                }
            }
            byDay.add(day);
            days.addAll(day.keySet());
        }
        List<Observation> observations = new ArrayList<>();
        int front = 0;
        for (LocalDate date : days) {
            int best = -1;
            for (int i = front; i < order.size(); i++) {
                Candle candle = byDay.get(i).get(date);
                if (candle != null && candle.volume() >= (long) MIN_LOTS * order.get(i).lotSize()
                        && (best < 0 || candle.volume() > byDay.get(best).get(date).volume())) {
                    best = i;
                }
            }
            if (best >= 0) {
                front = best;
                observations.add(new Observation(date, byDay.get(best).get(date).close()));
            }
        }
        return observations;
    }
}
