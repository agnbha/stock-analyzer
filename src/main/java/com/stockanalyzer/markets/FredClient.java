package com.stockanalyzer.markets;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockanalyzer.util.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads daily series from FRED, the Federal Reserve Bank of St. Louis's data service, through its API, which needs a
 * free key (fred.stlouisfed.org, "API Keys" under My Account). The CSV its charts download is no use to a program:
 * FRED stalls a request from anything but a browser or curl. Here it supplies the EIA's Brent and WTI spot prices,
 * about a week behind the market.
 */
public final class FredClient {

    private final HttpClient httpClient;
    private final String apiUrl;
    private final String apiKey;

    /** @param apiKey the FRED API key, or null when none is configured: every read then says how to get one */
    public FredClient(HttpClient httpClient, String apiUrl, String apiKey) {
        this.httpClient = httpClient;
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey.strip();
    }

    /** The series' readings from {@code from} on, oldest first. */
    public List<Observation> fetch(String series, LocalDate from) {
        if (apiKey == null) {
            throw new MarketDataException("no FRED API key: get one free at fred.stlouisfed.org (My Account, API Keys)"
                    + " and set FRED_API_KEY", null);
        }
        String url = apiUrl + "/fred/series/observations?file_type=json&series_id="
                + URLEncoder.encode(series, StandardCharsets.UTF_8) + "&observation_start=" + from
                + "&api_key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", "stock-analyzer/1.0")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MarketDataException("interrupted while reading " + series + " from FRED", e);
        } catch (Exception e) {
            // The message, never the request: its address carries the key.
            throw new MarketDataException("could not reach FRED for " + series + ": " + e.getClass().getSimpleName(), null);
        }
        return parse(series, response.statusCode(), response.body());
    }

    /**
     * The API's answer: {@code {"observations": [{"date": "2026-11-10", "value": "91.04"}, ...]}}, oldest first, with
     * "." for a day without a reading (a US holiday), which is left out. A refusal carries {@code error_message}.
     */
    static List<Observation> parse(String series, int status, String body) {
        JsonNode root;
        try {
            root = JsonMapper.INSTANCE.readTree(body);
        } catch (Exception e) {
            throw new MarketDataException("FRED answered HTTP " + status + " for " + series + " with no JSON", null);
        }
        if (status / 100 != 2 || !root.has("observations")) {
            String why = root.path("error_message").asText("no observations");
            throw new MarketDataException("FRED refused " + series + " (HTTP " + status + "): " + why.strip(), null);
        }
        List<Observation> observations = new ArrayList<>();
        for (JsonNode reading : root.path("observations")) {
            String value = reading.path("value").asText("").strip();
            if (value.isEmpty() || value.equals(".")) {
                continue;
            }
            try {
                observations.add(new Observation(LocalDate.parse(reading.path("date").asText()), Double.parseDouble(value)));
            } catch (DateTimeParseException | NumberFormatException e) {
                throw new MarketDataException("FRED sent an unreadable reading for " + series + ": " + reading, e);
            }
        }
        return observations;
    }
}
