package com.stockanalyzer.markets;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads NSE's participant-wise open interest in equity derivatives: at each day's end, how many contracts clients, DIIs,
 * FIIs and proprietary traders held long and short. NSE's archive keeps a small CSV file a trading day, with no key.
 * A weekend, a holiday, or today before NSE has published the file has none.
 */
public final class NseParticipantOiClient {

    /** Who holds the positions, as the file names them. */
    public enum Participant {
        CLIENT("Client"), DII("DII"), FII("FII"), PRO("Pro");

        private final String label;

        Participant(String label) {
            this.label = label;
        }
    }

    /** Which side of index futures. */
    public enum Side { LONG, SHORT }

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("ddMMyyyy", Locale.ROOT);

    /** One participant's index-futures contracts at a day's end. */
    record Position(long longContracts, long shortContracts) {
        long get(Side side) {
            return side == Side.LONG ? longContracts : shortContracts;
        }
    }

    private final HttpClient httpClient;
    private final String urlPattern;
    private final Clock clock;
    private final ZoneId zone;
    private final int historyDays;
    private final Duration pause;
    private boolean asked;
    private final Map<LocalDate, Optional<Map<Participant, Position>>> days = new HashMap<>();
    private final Map<LocalDate, MarketDataException> failed = new HashMap<>();

    /**
     * @param urlPattern  the file's address with {@code %s} for the date, written ddMMyyyy
     * @param historyDays how far back a first read goes at most: a file a day, so a read of years would be slow
     * @param pause       between two files
     */
    public NseParticipantOiClient(HttpClient httpClient, String urlPattern, Clock clock, ZoneId zone, int historyDays,
                                  Duration pause) {
        this.httpClient = httpClient;
        this.urlPattern = urlPattern;
        this.clock = clock;
        this.zone = zone;
        this.historyDays = historyDays;
        this.pause = pause;
    }

    /** One participant's index-futures contracts on one side, a weekday at a time from {@code from} to today. */
    public List<Observation> fetch(Participant participant, Side side, LocalDate from) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate start = from.isBefore(today.minusDays(historyDays)) ? today.minusDays(historyDays) : from;
        List<Observation> observations = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(today); date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            Optional<Map<Participant, Position>> day = day(date);
            if (day.isPresent()) {
                Position position = day.get().get(participant);
                if (position == null) {
                    throw new MarketDataException("NSE's open interest file for " + date + " has no "
                            + participant.label + " row", null);
                }
                observations.add(new Observation(date, position.get(side)));
            }
        }
        return observations;
    }

    private Optional<Map<Participant, Position>> day(LocalDate date) {
        if (failed.containsKey(date)) {
            throw failed.get(date);
        }
        Optional<Map<Participant, Position>> day = days.get(date);
        if (day == null) {
            try {
                day = download(date).map(NseParticipantOiClient::parse);
            } catch (MarketDataException e) {
                failed.put(date, e);
                throw e;
            }
            days.put(date, day);
        }
        return day;
    }

    private Optional<String> download(LocalDate date) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(String.format(urlPattern, FILE_DATE.format(date))))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/csv")
                .header("User-Agent", "stock-analyzer/1.0")
                .GET()
                .build();
        try {
            if (asked && !pause.isZero()) {
                Thread.sleep(pause.toMillis());
            }
            asked = true;
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() / 100 != 2) {
                throw new MarketDataException("NSE's open interest file for " + date + " answered HTTP "
                        + response.statusCode(), null);
            }
            return Optional.of(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MarketDataException("interrupted while reading NSE's open interest", e);
        } catch (java.io.IOException e) {
            throw new MarketDataException("could not reach NSE's open interest file for " + date + ": "
                    + e.getMessage(), e);
        }
    }

    /**
     * A title line, a header naming the columns ("Client Type", "Future Index Long", "Future Index Short", ...) and a
     * row a participant, then a total. The columns are found by name, so one added or moved does not shift the figures.
     */
    static Map<Participant, Position> parse(String csv) {
        String[] lines = csv.strip().split("\r?\n");
        int header = -1;
        for (int i = 0; i < lines.length && header < 0; i++) {
            if (lines[i].toLowerCase(Locale.ROOT).startsWith("client type")) {
                header = i;
            }
        }
        if (header < 0) {
            throw new MarketDataException("NSE's open interest file has no header row; it began: "
                    + lines[0].substring(0, Math.min(80, lines[0].length())), null);
        }
        List<String> columns = cells(lines[header]);
        int longColumn = columns.indexOf("future index long");
        int shortColumn = columns.indexOf("future index short");
        if (longColumn < 0 || shortColumn < 0) {
            throw new MarketDataException("NSE's open interest file has no index futures columns: " + columns, null);
        }
        Map<Participant, Position> positions = new HashMap<>();
        for (int i = header + 1; i < lines.length; i++) {
            List<String> row = cells(lines[i]);
            for (Participant participant : Participant.values()) {
                if (!row.isEmpty() && row.getFirst().equals(participant.label.toLowerCase(Locale.ROOT))) {
                    try {
                        positions.put(participant, new Position(Long.parseLong(row.get(longColumn)),
                                Long.parseLong(row.get(shortColumn))));
                    } catch (NumberFormatException | IndexOutOfBoundsException e) {
                        throw new MarketDataException("NSE's open interest file has an unreadable row: " + lines[i], e);
                    }
                }
            }
        }
        return positions;
    }

    private static List<String> cells(String line) {
        List<String> cells = new ArrayList<>();
        for (String cell : line.split(",", -1)) {
            cells.add(cell.replace("\"", "").strip().toLowerCase(Locale.ROOT));
        }
        return cells;
    }
}
