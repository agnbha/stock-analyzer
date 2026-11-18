package com.stockanalyzer.markets;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the US Treasury's daily par yield curve from the Treasury's own site, a CSV file a calendar year, with no key.
 * The rates are published the evening of the US trading day, so the evening run here has the day before. One file holds
 * every maturity, so a run reads each year once and answers every maturity from it.
 */
public final class TreasuryYieldClient {

    private static final DateTimeFormatter US_DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    private final HttpClient httpClient;
    private final String baseUrl;
    private final Map<Integer, String> years = new HashMap<>();

    public TreasuryYieldClient(HttpClient httpClient, String baseUrl) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * One maturity's yields in percent from {@code from} on, oldest first.
     *
     * @param maturity the file's column for it: "10 Yr", "2 Yr"
     */
    public List<Observation> fetch(String maturity, LocalDate from) {
        List<Observation> observations = new ArrayList<>();
        for (int year = from.getYear(); year <= LocalDate.now().getYear(); year++) {
            for (Observation observation : parse(maturity, year(year))) {
                if (!observation.date().isBefore(from)) {
                    observations.add(observation);
                }
            }
        }
        observations.sort(Comparator.comparing(Observation::date));
        return observations;
    }

    private String year(int year) {
        return years.computeIfAbsent(year, this::download);
    }

    private String download(int year) {
        String url = baseUrl + "/resource-center/data-chart-center/interest-rates/daily-treasury-rates.csv/" + year
                + "/all?type=daily_treasury_yield_curve&field_tdr_date_value=" + year + "&page&_format=csv";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
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
            throw new MarketDataException("interrupted while reading the Treasury's " + year + " yields", e);
        } catch (Exception e) {
            throw new MarketDataException("could not reach the Treasury for " + year + ": " + e.getMessage(), e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new MarketDataException("the Treasury answered HTTP " + response.statusCode() + " for " + year, null);
        }
        return response.body();
    }

    /**
     * The file is a header naming the maturities in quotes ({@code Date,"1 Mo",...,"2 Yr",...,"10 Yr",...}) and then a
     * day a line, newest first, dated month/day/year. A maturity without a rate that day is blank and left out. A file
     * without the maturity's column, such as an error page, is refused rather than read as no readings.
     */
    static List<Observation> parse(String maturity, String csv) {
        String[] lines = csv.strip().split("\r?\n");
        if (lines.length == 0 || lines[0].isBlank()) {
            return List.of();
        }
        String[] header = lines[0].split(",", -1);
        int column = -1;
        for (int i = 0; i < header.length; i++) {
            if (header[i].replace("\"", "").strip().equalsIgnoreCase(maturity)) {
                column = i;
            }
        }
        if (column < 0 || !header[0].replace("\"", "").strip().equalsIgnoreCase("Date")) {
            throw new MarketDataException("the Treasury's file has no \"" + maturity + "\" column; it began: "
                    + lines[0].substring(0, Math.min(80, lines[0].length())), null);
        }
        List<Observation> observations = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String[] cells = lines[i].split(",", -1);
            if (cells.length <= column || cells[column].isBlank()) {
                continue;
            }
            try {
                observations.add(new Observation(LocalDate.parse(cells[0].replace("\"", "").strip(), US_DATE),
                        Double.parseDouble(cells[column].strip())));
            } catch (DateTimeParseException | NumberFormatException e) {
                throw new MarketDataException("the Treasury's file has an unreadable line: " + lines[i], e);
            }
        }
        observations.sort(Comparator.comparing(Observation::date));
        return observations;
    }
}
