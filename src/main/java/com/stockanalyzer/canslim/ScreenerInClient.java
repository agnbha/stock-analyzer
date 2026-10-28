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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a query against screener.in's query screen ({@code /screen/raw/}).
 *
 * <p><b>Authentication.</b> Custom queries need a signed-in account, and
 * screener.in signs in through Google (or a password) in a browser - a flow
 * that cannot be completed from here, and should not be: it would mean holding
 * the Google credentials. Instead this sends the session cookie that the
 * browser already holds. Sign in at screener.in as usual, copy the
 * {@code sessionid} cookie, and set {@code SCREENER_SESSION_ID}. When the
 * session expires the site redirects to its login page, which surfaces here as
 * {@link ScreenerException#needsLogin()}.
 *
 * <p><b>Politeness.</b> Requests are spaced at least {@code minIntervalMillis}
 * apart and stop after {@code maxPages}; one scan is a handful of page loads,
 * the same as a person paging through the results.
 */
public final class ScreenerInClient implements ScreenerClient {

    private static final Logger log = LoggerFactory.getLogger(ScreenerInClient.class);
    private static final int PAGE_SIZE = 50;
    private static final Pattern ERROR_TEXT = Pattern.compile(
            "class=\"[^\"]*(?:error|flash)[^\"]*\"[^>]*>\\s*(.*?)\\s*</", Pattern.DOTALL);

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String sessionCookie;
    private final int maxPages;
    private final long minIntervalMillis;
    private long lastRequestAt;

    public ScreenerInClient(HttpClient httpClient, String baseUrl, String sessionId,
                            int maxPages, long minIntervalMillis) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.sessionCookie = cookieHeader(sessionId);
        this.maxPages = maxPages;
        this.minIntervalMillis = minIntervalMillis;
    }

    @Override
    public boolean hasSession() {
        return sessionCookie != null;
    }

    @Override
    public List<ScreenedStock> screen(String query) {
        if (sessionCookie == null) {
            throw new ScreenerException("No screener.in session configured. Sign in at "
                    + "https://www.screener.in in a browser, copy the 'sessionid' cookie, and set "
                    + "SCREENER_SESSION_ID (or screener.session.id).", true);
        }
        // Keyed by symbol: a company that moves between pages while we read them
        // must not be counted twice.
        Map<String, ScreenedStock> results = new LinkedHashMap<>();
        int skipped = 0;
        int totalPages = 1;
        for (int page = 1; page <= Math.min(totalPages, maxPages); page++) {
            ScreenerResultsParser.Page parsed = fetchPage(query, page);
            parsed.stocks().forEach(stock -> results.putIfAbsent(stock.symbol(), stock));
            skipped += parsed.skippedNonNse();
            totalPages = parsed.totalPages();
            if (parsed.stocks().isEmpty()) {
                break;
            }
        }
        if (totalPages > maxPages) {
            log.warn("Query matched {} pages of results; read the first {} ({} companies). "
                    + "Tighten the query or raise canslim.screener.max.pages.", totalPages, maxPages, results.size());
        }
        if (skipped > 0) {
            log.info("Skipped {} BSE-only companies: there is no NSE symbol to fetch prices for", skipped);
        }
        return new ArrayList<>(results.values());
    }

    private ScreenerResultsParser.Page fetchPage(String query, int page) {
        String url = baseUrl + "/screen/raw/?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&limit=" + PAGE_SIZE + "&page=" + page;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/html")
                .header("Cookie", sessionCookie)
                .header("User-Agent", "stock-analyzer/1.0 (personal CAN SLIM scan)")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            pace();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScreenerException("Interrupted while querying screener.in", e);
        } catch (Exception e) {
            throw new ScreenerException("Could not reach screener.in: " + e.getMessage(), e);
        }

        int status = response.statusCode();
        String location = response.headers().firstValue("Location").orElse("");
        if (status / 100 == 3 && (location.contains("/login") || location.contains("/register"))
                || status == 401 || status == 403) {
            throw new ScreenerException("screener.in did not accept the session (HTTP " + status
                    + "). The 'sessionid' cookie has expired or is wrong: sign in again in a browser and "
                    + "update SCREENER_SESSION_ID.", true);
        }
        if (status / 100 != 2) {
            throw new ScreenerException("screener.in answered HTTP " + status + " for page " + page
                    + (location.isEmpty() ? "" : " (redirect to " + location + ")"), false);
        }

        ScreenerResultsParser.Page parsed = ScreenerResultsParser.parse(response.body());
        if (parsed == null) {
            throw new ScreenerException(explainMissingTable(response.body()), isLoginPage(response.body()));
        }
        log.debug("screener.in page {} of {}: {} companies", page, parsed.totalPages(), parsed.stocks().size());
        return parsed;
    }

    /** No results table: either a login form, or screener rejected the query and said why. */
    private static String explainMissingTable(String body) {
        if (isLoginPage(body)) {
            return "screener.in served its login page: the session has expired. Sign in again in a "
                    + "browser and update SCREENER_SESSION_ID.";
        }
        Matcher error = ERROR_TEXT.matcher(body);
        while (error.find()) {
            String text = error.group(1).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
            if (!text.isEmpty()) {
                return "screener.in rejected the query: " + text;
            }
        }
        return "screener.in returned no results table - check the query in the screener.in editor";
    }

    private static boolean isLoginPage(String body) {
        return body.contains("name=\"password\"") || body.contains("/login/google/");
    }

    private synchronized void pace() throws InterruptedException {
        long wait = lastRequestAt + minIntervalMillis - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastRequestAt = System.currentTimeMillis();
    }

    /** Accepts the bare cookie value, or a whole {@code name=value; ...} header copied from the browser. */
    private static String cookieHeader(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String trimmed = sessionId.trim();
        return trimmed.contains("=") ? trimmed : "sessionid=" + trimmed;
    }
}
