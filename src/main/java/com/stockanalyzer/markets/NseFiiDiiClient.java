package com.stockanalyzer.markets;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockanalyzer.util.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads NSE's provisional figures for what foreign (FII/FPI) and domestic (DII) institutions bought and sold in the cash
 * market, the numbers on NSE's "FII/DII trading activity" page, from the JSON that page itself loads, with no key. NSE
 * publishes a day's figures that evening and keeps them up until the next day's replace them, so there is no history to
 * read: a series grows by one day each time it is read, and a run that reads it the next afternoon still has the day.
 */
public final class NseFiiDiiClient {

    /** Who traded. */
    public enum Category {
        FII("FII/FPI"), DII("DII");

        private final String label;

        Category(String label) {
            this.label = label;
        }
    }

    /** Which figure, each in rupees crore. */
    public enum Figure { BUY, SELL, NET }

    private final HttpClient httpClient;
    private final String url;
    private Map<Category, Day> latest;
    private MarketDataException failure;

    /** One category's day. */
    record Day(LocalDate date, double buy, double sell, double net) {
        double get(Figure figure) {
            return switch (figure) {
                case BUY -> buy;
                case SELL -> sell;
                case NET -> net;
            };
        }
    }

    /** @param httpClient one that follows redirects: NSE's address answers from its other domain */
    public NseFiiDiiClient(HttpClient httpClient, String url) {
        this.httpClient = httpClient;
        this.url = url;
    }

    /** The latest day's figure, if that day is not before {@code from}. */
    public List<Observation> fetch(Category category, Figure figure, LocalDate from) {
        Day day = latest().get(category);
        if (day == null) {
            throw new MarketDataException("NSE's FII/DII figures have no " + category.label + " row", null);
        }
        return day.date().isBefore(from) ? List.of() : List.of(new Observation(day.date(), day.get(figure)));
    }

    private Map<Category, Day> latest() {
        if (failure != null) {
            throw failure;
        }
        if (latest == null) {
            try {
                latest = parse(download());
            } catch (MarketDataException e) {
                failure = e;
                throw e;
            }
        }
        return latest;
    }

    private String download() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", "stock-analyzer/1.0")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new MarketDataException("NSE's FII/DII figures answered HTTP " + response.statusCode(), null);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MarketDataException("interrupted while reading NSE's FII/DII figures", e);
        } catch (java.io.IOException e) {
            throw new MarketDataException("could not reach NSE's FII/DII figures: " + e.getMessage(), e);
        }
    }

    /**
     * A list with a row for each category: {@code {"category":"DII","date":"18-Nov-2026","buyValue":"14602.25",
     * "sellValue":"17762.15","netValue":"-3159.9"}}. Anything else, such as an error page, is refused.
     */
    static Map<Category, Day> parse(String json) {
        JsonNode rows;
        try {
            rows = JsonMapper.INSTANCE.readTree(json);
        } catch (Exception e) {
            throw new MarketDataException("NSE did not send its FII/DII figures as JSON; it began: "
                    + json.substring(0, Math.min(80, json.length())), e);
        }
        if (!rows.isArray()) {
            throw new MarketDataException("NSE's FII/DII figures are not a list", null);
        }
        Map<Category, Day> days = new HashMap<>();
        for (JsonNode row : rows) {
            for (Category category : Category.values()) {
                if (category.label.equals(row.path("category").asText())) {
                    try {
                        days.put(category, new Day(LocalDate.parse(row.path("date").asText(), NsdlFpiClient.NSDL_DATE),
                                number(row, "buyValue"), number(row, "sellValue"), number(row, "netValue")));
                    } catch (DateTimeParseException e) {
                        throw new MarketDataException("NSE's FII/DII row has an unreadable date: " + row, e);
                    }
                }
            }
        }
        return days;
    }

    private static double number(JsonNode row, String field) {
        try {
            return Double.parseDouble(row.path(field).asText().replace(",", ""));
        } catch (NumberFormatException e) {
            throw new MarketDataException("NSE's FII/DII row has an unreadable " + field + ": " + row, e);
        }
    }
}
