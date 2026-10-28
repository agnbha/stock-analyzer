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
 * <p><b>Politeness.</b> One request per scan, for the first page of results. screener.in's
 * robots.txt asks automated clients not to use its {@code page}, {@code limit}, {@code sort}
 * or {@code q} parameters, so none is sent: a query that matches more than a page says so
 * ({@link ScreenerClient.Result#moreAvailable()}) and should be tightened instead.
 */
public final class ScreenerInClient implements ScreenerClient {

    private static final Logger log = LoggerFactory.getLogger(ScreenerInClient.class);
    private static final Pattern ERROR_TEXT = Pattern.compile(
            "class=\"[^\"]*(?:error|flash)[^\"]*\"[^>]*>\\s*(.*?)\\s*</", Pattern.DOTALL);

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String sessionCookie;

    public ScreenerInClient(HttpClient httpClient, String baseUrl, String sessionId) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.sessionCookie = cookieHeader(sessionId);
    }

    @Override
    public boolean hasSession() {
        return sessionCookie != null;
    }

    @Override
    public Result screen(String query) {
        if (sessionCookie == null) {
            throw new ScreenerException("No screener.in session configured. Sign in at "
                    + "https://www.screener.in in a browser, copy the 'sessionid' cookie, and set "
                    + "SCREENER_SESSION_ID (or screener.session.id).", true);
        }
        ScreenerResultsParser.Page page = fetchPage(query);
        boolean more = page.totalPages() > 1;
        if (more) {
            log.warn("The query matched {} pages of results; read only the first ({} companies), because "
                    + "screener.in's robots.txt asks automated clients not to page. Tighten the query.",
                    page.totalPages(), page.stocks().size());
        }
        if (page.skippedNonNse() > 0) {
            log.info("Skipped {} BSE-only companies: there is no NSE symbol to fetch prices for",
                    page.skippedNonNse());
        }
        return new Result(page.stocks(), more);
    }

    private ScreenerResultsParser.Page fetchPage(String query) {
        String url = baseUrl + "/screen/raw/?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
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
            throw new ScreenerException("screener.in answered HTTP " + status
                    + (location.isEmpty() ? "" : " (redirect to " + location + ")"), false);
        }

        ScreenerResultsParser.Page parsed = ScreenerResultsParser.parse(response.body());
        if (parsed == null) {
            throw new ScreenerException(explainMissingTable(response.body()), isLoginPage(response.body()));
        }
        log.debug("screener.in first of {} pages: {} companies", parsed.totalPages(), parsed.stocks().size());
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

    /** Accepts the bare cookie value, or a whole {@code name=value; ...} header copied from the browser. */
    private static String cookieHeader(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String trimmed = sessionId.trim();
        return trimmed.contains("=") ? trimmed : "sessionid=" + trimmed;
    }
}
