package com.stockanalyzer.canslim;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Reads fundamentals from screener.in's public company pages. No account is
 * needed for these - unlike query results - and robots.txt allows
 * {@code /company/<SYMBOL>/}; it asks crawlers to stay off search, sort and
 * page parameters, which this never uses.
 *
 * <p>Pages are fetched one at a time, {@code minIntervalMillis} apart, and the
 * caller caches what comes back: the numbers change when a company reports a
 * quarter, so a page is read again only when that may have happened
 * ({@link FundamentalsRefreshPolicy}), not on a timer.
 */
public final class ScreenerCompanyClient implements CompanyFundamentalsClient {

    private static final Logger log = LoggerFactory.getLogger(ScreenerCompanyClient.class);

    private final HttpClient httpClient;
    private final String baseUrl;
    private final long minIntervalMillis;
    private long lastRequestAt;

    public ScreenerCompanyClient(HttpClient httpClient, String baseUrl, long minIntervalMillis) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.minIntervalMillis = minIntervalMillis;
    }

    /**
     * The consolidated statements first, as screener does for any company that
     * has them, then the standalone ones for a company that does not.
     */
    @Override
    public Optional<CompanyFundamentals> fetch(String symbol) {
        String encoded = URLEncoder.encode(symbol, StandardCharsets.UTF_8);
        for (String path : new String[]{"/company/" + encoded + "/consolidated/", "/company/" + encoded + "/"}) {
            String html = get(baseUrl + path, symbol);
            if (html == null) {
                continue;
            }
            CompanyFundamentals fundamentals = ScreenerCompanyParser.fundamentals(html);
            if (fundamentals.ratios().containsKey(ScreenerRatios.QTR_PROFIT_GROWTH)
                    || fundamentals.ratios().containsKey(ScreenerRatios.PROFIT_GROWTH_3Y)) {
                return Optional.of(fundamentals);
            }
            log.debug("{}{} carried no growth figures", symbol, path);
        }
        return Optional.empty();
    }

    /** The page body, or null when the page does not exist. */
    private String get(String url, String symbol) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/html")
                .header("User-Agent", "stock-analyzer/1.0 (personal CAN SLIM scan)")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            pace();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScreenerException("Interrupted while reading screener.in for " + symbol, e);
        } catch (Exception e) {
            throw new ScreenerException("Could not reach screener.in for " + symbol + ": " + e.getMessage(), e);
        }
        int status = response.statusCode();
        if (status == 404 || status / 100 == 3) {
            return null;
        }
        if (status == 429) {
            throw new ScreenerException("screener.in is rate limiting (HTTP 429) - stopping rather than "
                    + "asking again; the rest will be fetched on a later run", false);
        }
        if (status / 100 != 2) {
            throw new ScreenerException("screener.in answered HTTP " + status + " for " + symbol, false);
        }
        return response.body();
    }

    private synchronized void pace() throws InterruptedException {
        long wait = lastRequestAt + minIntervalMillis - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastRequestAt = System.currentTimeMillis();
    }
}
