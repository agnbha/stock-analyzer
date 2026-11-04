package com.stockanalyzer.trade;

import com.stockanalyzer.model.Side;
import com.stockanalyzer.model.Trade;
import com.stockanalyzer.store.TradeNoteRepository;
import com.stockanalyzer.store.TradeRepository;
import com.stockanalyzer.util.MarketClock;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Records what you thought when you took a trade - a sentiment and some words - against the fills
 * of that call.
 *
 * <p>A call is rarely one fill. An order for a few hundred shares arrives as dozens, and a position
 * can be built from several orders through the day. So a note is addressed to a call (a symbol, a
 * side and a day, narrowed by the time or the order when the day had more than one), and is written
 * against every fill that call is made of. Notes live in the journal, not in a chart, so they
 * survive re-imports and show up next to the trade wherever the trade does.
 */
public final class TradeNoteService {

    /** How far either side of the time you give a fill may be and still belong to "the call at that time". */
    public static final int AT_WINDOW_MINUTES = 3;

    private final TradeRepository trades;
    private final TradeNoteRepository notes;
    private final MarketClock clock;

    public TradeNoteService(TradeRepository trades, TradeNoteRepository notes, MarketClock clock) {
        this.trades = trades;
        this.notes = notes;
        this.clock = clock;
    }

    /**
     * Which fills a note is for.
     *
     * @param at      narrows to the call made around this time of day, in the market's time zone
     * @param orderId narrows to one broker order
     */
    public record Selection(String symbol, Side side, LocalDate date, LocalTime at, String orderId) {
    }

    /** One purchase or sale: the fills of one order, or of one second when the fill names no order. */
    public record Call(String orderId, LocalDate date, long firstFillAt, Side side, int quantity,
                       double averagePrice, int fills) {
    }

    public record Result(List<Trade> fills, List<Call> calls) {
    }

    /** A call that has a note, for listing. */
    public record Entry(LocalDate date, long firstFillAt, String symbol, Side side, int quantity, double averagePrice,
                        int fills, String sentiment, String note) {
    }

    /**
     * Writes the note against every selected fill, replacing one already there.
     *
     * @throws IllegalArgumentException when nothing is selected, or there is neither a sentiment nor text
     */
    public Result note(Selection selection, String sentiment, String text, long nowEpoch) {
        String label = normaliseSentiment(sentiment);
        String words = text == null || text.isBlank() ? null : text.strip();
        if (label == null && words == null) {
            throw new IllegalArgumentException("Give a --sentiment, some --text, or both");
        }
        Result selected = select(selection);
        notes.upsertAll(selected.fills().stream().map(Trade::id).toList(), label, words, nowEpoch);
        return selected;
    }

    /** Removes the note from every selected fill. */
    public Result clear(Selection selection) {
        Result selected = select(selection);
        notes.deleteAll(selected.fills().stream().map(Trade::id).toList());
        return selected;
    }

    /** The noted calls in the date range, oldest first. */
    public List<Entry> list(LocalDate from, LocalDate to, String symbolOrNull) {
        Map<String, List<TradeNoteRepository.Noted>> byCall = new LinkedHashMap<>();
        for (TradeNoteRepository.Noted noted : notes.findBetween(from, to, symbolOrNull)) {
            String key = noted.sessionDate() + "|" + noted.symbol() + "|" + noted.side() + "|" + noted.sentiment()
                    + "|" + noted.note() + "|" + noted.executedTs() / 60;
            byCall.computeIfAbsent(key, k -> new ArrayList<>()).add(noted);
        }
        List<Entry> entries = new ArrayList<>();
        for (List<TradeNoteRepository.Noted> fills : byCall.values()) {
            TradeNoteRepository.Noted first = fills.getFirst();
            int quantity = fills.stream().mapToInt(TradeNoteRepository.Noted::quantity).sum();
            double cost = fills.stream().mapToDouble(f -> f.quantity() * f.price()).sum();
            entries.add(new Entry(first.sessionDate(), first.executedTs(), first.symbol(), first.side(), quantity,
                    cost / quantity, fills.size(), first.sentiment(), first.note()));
        }
        return entries;
    }

    private Result select(Selection selection) {
        List<Trade> onTheDay = trades.findRange(selection.date(), selection.date()).stream()
                .filter(t -> t.symbol().equalsIgnoreCase(selection.symbol()))
                .sorted(Comparator.comparingLong(Trade::executedTs).thenComparingLong(Trade::id))
                .toList();
        List<Trade> chosen = onTheDay.stream()
                .filter(t -> t.side() == selection.side())
                .filter(t -> selection.orderId() == null || selection.orderId().equals(t.orderId()))
                .filter(t -> selection.at() == null || Math.abs(t.executedTs()
                        - clock.epochOf(selection.date(), selection.at())) <= AT_WINDOW_MINUTES * 60L)
                .toList();
        if (chosen.isEmpty()) {
            throw new IllegalArgumentException(nothingFound(selection, onTheDay));
        }
        return new Result(chosen, calls(chosen));
    }

    private String nothingFound(Selection selection, List<Trade> onTheDay) {
        StringBuilder message = new StringBuilder("No ").append(selection.side()).append(" fills for ")
                .append(selection.symbol().toUpperCase(Locale.ROOT)).append(" on ").append(selection.date());
        if (selection.at() != null) {
            message.append(" within ").append(AT_WINDOW_MINUTES).append(" minutes of ").append(selection.at());
        }
        if (selection.orderId() != null) {
            message.append(" in order ").append(selection.orderId());
        }
        if (onTheDay.isEmpty()) {
            return message.append(". There are no fills for that symbol that day.").toString();
        }
        message.append(". Fills for that symbol that day:");
        for (Call call : calls(onTheDay)) {
            message.append(String.format(Locale.ROOT, "%n  %s %s  %d shares at %.2f  (order %s)",
                    clock.timeOf(call.firstFillAt()).withNano(0), call.side(), call.quantity(), call.averagePrice(),
                    call.orderId() == null ? "-" : call.orderId()));
        }
        return message.toString();
    }

    private static List<Call> calls(List<Trade> fills) {
        Map<String, List<Trade>> byOrder = new LinkedHashMap<>();
        for (Trade fill : fills) {
            String key = fill.side() + "|" + (fill.orderId() != null ? fill.orderId() : "at" + fill.executedTs());
            byOrder.computeIfAbsent(key, k -> new ArrayList<>()).add(fill);
        }
        List<Call> calls = new ArrayList<>();
        for (List<Trade> group : byOrder.values()) {
            Trade first = group.getFirst();
            int quantity = group.stream().mapToInt(Trade::quantity).sum();
            double cost = group.stream().mapToDouble(Trade::turnover).sum();
            calls.add(new Call(first.orderId(), first.sessionDate(), first.executedTs(), first.side(), quantity,
                    cost / quantity, group.size()));
        }
        return calls;
    }

    /** BULLISH, bullish and " Bullish " are one sentiment; anything that is not a letter or digit becomes an underscore. */
    static String normaliseSentiment(String sentiment) {
        if (sentiment == null) {
            return null;
        }
        String code = sentiment.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return Objects.equals(code, "") ? null : code;
    }
}
