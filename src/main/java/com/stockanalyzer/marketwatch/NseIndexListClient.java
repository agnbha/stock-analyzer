package com.stockanalyzer.marketwatch;

import com.stockanalyzer.markets.MarketDataException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads an NSE index's members from the list NSE keeps in its archive for each index: a small CSV file, one row a
 * company (Company Name, Industry, Symbol, Series, ISIN Code), with no key. The file is named after the index in lower
 * case without its spaces: NIFTY SMALLCAP 250 is {@code ind_niftysmallcap250list.csv}.
 */
public final class NseIndexListClient {

    private final HttpClient httpClient;
    private final String urlPattern;

    /** @param urlPattern the list's address with {@code %s} for the index's name, written as the file names it */
    public NseIndexListClient(HttpClient httpClient, String urlPattern) {
        this.httpClient = httpClient;
        this.urlPattern = urlPattern;
    }

    /** The index's name as NSE's file names spell it: NIFTY MIDCAP 150 is niftymidcap150. */
    static String fileName(String index) {
        return index.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    public String url(String index) {
        return String.format(urlPattern, fileName(index));
    }

    /** The index's members' symbols, in the list's order. */
    public List<String> members(String index) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url(index)))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/csv")
                .header("User-Agent", "stock-analyzer/1.0")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                throw new MarketDataException("NSE has no list for " + index + " (" + url(index) + "): check the "
                        + "index's name, or give its members in the settings", null);
            }
            if (response.statusCode() / 100 != 2) {
                throw new MarketDataException("NSE's list for " + index + " answered HTTP " + response.statusCode(), null);
            }
            return parse(response.body(), index);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MarketDataException("interrupted while reading NSE's list for " + index, e);
        } catch (java.io.IOException e) {
            throw new MarketDataException("could not reach NSE's list for " + index + ": " + e.getMessage(), e);
        }
    }

    /**
     * The Symbol column of every row after the header, found by its name so a column added or moved does not shift it.
     * A company's name may hold a comma inside quotes.
     */
    static List<String> parse(String csv, String index) {
        String[] lines = csv.strip().split("\r?\n");
        List<String> header = cells(lines[0]);
        int column = -1;
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i).equalsIgnoreCase("symbol")) {
                column = i;
            }
        }
        if (column < 0) {
            throw new MarketDataException("NSE's list for " + index + " has no Symbol column; it began: "
                    + lines[0].substring(0, Math.min(80, lines[0].length())), null);
        }
        Set<String> symbols = new LinkedHashSet<>();
        for (int i = 1; i < lines.length; i++) {
            List<String> row = cells(lines[i]);
            if (row.size() > column && !row.get(column).isBlank()) {
                symbols.add(row.get(column).strip().toUpperCase(Locale.ROOT));
            }
        }
        if (symbols.isEmpty()) {
            throw new MarketDataException("NSE's list for " + index + " names no companies", null);
        }
        return List.copyOf(symbols);
    }

    private static List<String> cells(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                cells.add(cell.toString().strip());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString().strip());
        return cells;
    }
}
