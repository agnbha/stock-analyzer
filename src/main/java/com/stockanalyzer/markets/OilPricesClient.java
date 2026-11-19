package com.stockanalyzer.markets;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads the EIA's daily Brent and WTI spot prices, in US dollars a barrel, from the open "oil-prices" dataset on GitHub
 * (github.com/datasets/oil-prices, public domain under ODC-PDDL), which republishes them as plain CSV files for
 * programs to download, with no key. The dataset is refreshed from the EIA weekly, so the latest price is about a week
 * old; the page's buttons show today's.
 */
public final class OilPricesClient {

    private final HttpClient httpClient;
    private final String baseUrl;

    /** @param baseUrl the dataset's data folder, under which {@code brent-daily.csv} and {@code wti-daily.csv} are */
    public OilPricesClient(HttpClient httpClient, String baseUrl) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * One benchmark's prices from {@code from} on, oldest first.
     *
     * @param benchmark the file's name in the dataset: "brent" or "wti"
     */
    public List<Observation> fetch(String benchmark, LocalDate from) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/" + benchmark + "-daily.csv"))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/csv")
                .header("User-Agent", "stock-analyzer/1.0")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MarketDataException("interrupted while reading the " + benchmark + " prices", e);
        } catch (Exception e) {
            throw new MarketDataException("could not reach the oil-prices dataset for " + benchmark + ": "
                    + e.getMessage(), e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new MarketDataException("the oil-prices dataset answered HTTP " + response.statusCode() + " for "
                    + benchmark, null);
        }
        return parse(benchmark, response.body()).stream().filter(o -> !o.date().isBefore(from)).toList();
    }

    /**
     * The file is a header, {@code Date,Price}, then a trading day and its price a line, oldest first. A day without a
     * price is left out. A file without that header, such as an error page, is refused rather than read as no prices.
     */
    static List<Observation> parse(String benchmark, String csv) {
        String[] lines = csv.strip().split("\r?\n");
        if (!lines[0].strip().equalsIgnoreCase("Date,Price")) {
            throw new MarketDataException("the oil-prices dataset did not send " + benchmark + " as CSV; it began: "
                    + lines[0].substring(0, Math.min(80, lines[0].length())), null);
        }
        List<Observation> observations = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String[] cells = lines[i].strip().split(",", -1);
            if (cells.length != 2 || cells[1].isBlank()) {
                continue;
            }
            try {
                observations.add(new Observation(LocalDate.parse(cells[0].strip()),
                        Double.parseDouble(cells[1].strip())));
            } catch (DateTimeParseException | NumberFormatException e) {
                throw new MarketDataException("the " + benchmark + " file has an unreadable line: " + lines[i], e);
            }
        }
        observations.sort(Comparator.comparing(Observation::date));
        return observations;
    }
}
