package com.stockanalyzer.canslim;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The listed equities of NSE and BSE, from the instrument list Groww publishes for its API
 * ({@code instrument.csv}, a public file of every tradable instrument).
 *
 * <p>Three things make "every symbol" more than a filter on the exchange column:
 * <ul>
 *   <li><b>Only equity shares.</b> The list also carries bonds, ETFs, mutual-fund units and
 *       F&amp;O contracts under the same segment. An equity share has an ISIN whose security
 *       type is {@code 01} ({@code INE002A01018}); debt is {@code 07} or {@code 08}, funds
 *       {@code INF}.</li>
 *   <li><b>One company once.</b> Most BSE companies are also on NSE under the same ISIN. NSE
 *       wins; BSE adds only the companies NSE does not list.</li>
 *   <li><b>Main board by default.</b> The SME platforms (NSE SM/ST, BSE X/XT/M/MT) trade in
 *       lots with narrow circuits and next to no liquidity; they cost thousands of requests
 *       and would be filtered out by the liquidity test anyway. They are a setting away.</li>
 * </ul>
 *
 * The 20 MB file is kept on disk and reused for {@code maxAge}; if a refresh fails, the old copy
 * is used rather than losing the scan.
 */
public final class GrowwInstrumentCatalog implements MarketCatalog {

    private static final Logger log = LoggerFactory.getLogger(GrowwInstrumentCatalog.class);
    private static final Pattern EQUITY_ISIN = Pattern.compile("^IN[E9]\\w{4}01\\w{3}$");

    /**
     * @param exchanges the exchanges to include, NSE and/or BSE
     * @param nseSeries NSE series to include, for example EQ, BE, BZ
     * @param bseSeries BSE groups to include for companies NSE does not list
     */
    public record Settings(String url, Path cacheFile, Duration maxAge, Set<String> exchanges,
                           Set<String> nseSeries, Set<String> bseSeries) {
    }

    private final HttpClient httpClient;
    private final Settings settings;

    public GrowwInstrumentCatalog(HttpClient httpClient, Settings settings) {
        this.httpClient = httpClient;
        this.settings = settings;
    }

    @Override
    public List<MarketListing> listings() {
        Path file = refreshedFile();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<MarketListing> listings = parse(reader, settings);
            log.info("Instrument list: {} listed equities ({} NSE, {} BSE-only)", listings.size(),
                    listings.stream().filter(l -> l.exchange().equals("NSE")).count(),
                    listings.stream().filter(l -> l.exchange().equals("BSE")).count());
            return listings;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the instrument list " + file, e);
        }
    }

    private Path refreshedFile() {
        Path cache = settings.cacheFile();
        boolean exists = Files.exists(cache);
        if (exists && age(cache).compareTo(settings.maxAge()) < 0) {
            return cache;
        }
        try {
            download(cache);
        } catch (IOException | RuntimeException e) {
            if (!exists) {
                throw new IllegalStateException("Could not download the instrument list from "
                        + settings.url() + ": " + e.getMessage(), e);
            }
            log.warn("Could not refresh the instrument list ({}); using the copy from {}", e.getMessage(),
                    Instant.ofEpochMilli(cache.toFile().lastModified()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while downloading the instrument list", e);
        }
        return cache;
    }

    private static Duration age(Path file) {
        return Duration.between(Instant.ofEpochMilli(file.toFile().lastModified()), Instant.now());
    }

    private void download(Path target) throws IOException, InterruptedException {
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "instruments", ".part");
        try {
            HttpResponse<Path> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(settings.url())).timeout(Duration.ofMinutes(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(temp));
            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + response.statusCode());
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("Downloaded the instrument list ({} MB)", Files.size(target) / 1_000_000);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Pure so it can be tested on a small file; the real one has 140,000 rows. */
    static List<MarketListing> parse(Reader reader, Settings settings) throws IOException {
        BufferedReader lines = reader instanceof BufferedReader b ? b : new BufferedReader(reader);
        String header = lines.readLine();
        if (header == null) {
            return List.of();
        }
        Map<String, Integer> column = new HashMap<>();
        List<String> names = splitCsv(header);
        for (int i = 0; i < names.size(); i++) {
            column.put(names.get(i), i);
        }
        for (String required : List.of("exchange", "exchange_token", "trading_symbol", "name", "instrument_type",
                "segment", "series", "isin")) {
            if (!column.containsKey(required)) {
                throw new IllegalStateException("The instrument list has no '" + required + "' column - "
                        + "has Groww changed its format? Columns: " + names);
            }
        }

        List<MarketListing> nse = new ArrayList<>();
        List<MarketListing> bse = new ArrayList<>();
        String line;
        while ((line = lines.readLine()) != null) {
            List<String> f = splitCsv(line);
            if (f.size() < names.size() || !"CASH".equals(f.get(column.get("segment")))
                    || !"EQ".equals(f.get(column.get("instrument_type")))) {
                continue;
            }
            String exchange = f.get(column.get("exchange"));
            String series = f.get(column.get("series"));
            String isin = f.get(column.get("isin"));
            if (!settings.exchanges().contains(exchange) || !EQUITY_ISIN.matcher(isin).matches()) {
                continue;
            }
            String symbol = f.get(column.get("trading_symbol"));
            String name = f.get(column.get("name"));
            if (exchange.equals("NSE") && settings.nseSeries().contains(series)) {
                nse.add(new MarketListing(symbol, "NSE", name, isin, series, symbol));
            } else if (exchange.equals("BSE") && settings.bseSeries().contains(series)) {
                // screener.in addresses a BSE-only company by its scrip code.
                bse.add(new MarketListing(symbol, "BSE", name, isin, series, f.get(column.get("exchange_token"))));
            }
        }

        Set<String> isins = new HashSet<>();
        Set<String> symbols = new HashSet<>();
        List<MarketListing> all = new ArrayList<>();
        nse.sort(Comparator.comparing(MarketListing::symbol));
        for (MarketListing listing : nse) {
            if (isins.add(listing.isin()) && symbols.add(listing.symbol())) {
                all.add(listing);
            }
        }
        bse.sort(Comparator.comparing(MarketListing::symbol));
        int collisions = 0;
        for (MarketListing listing : bse) {
            if (isins.contains(listing.isin())) {
                continue;
            }
            if (!symbols.add(listing.symbol())) {
                collisions++;
                continue;
            }
            isins.add(listing.isin());
            all.add(listing);
        }
        if (collisions > 0) {
            log.info("Skipped {} BSE-only companies whose symbol is already an NSE company's", collisions);
        }
        return all;
    }

    /** One CSV record: commas inside double quotes belong to the field, and "" is a literal quote. */
    static List<String> splitCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields;
    }
}
