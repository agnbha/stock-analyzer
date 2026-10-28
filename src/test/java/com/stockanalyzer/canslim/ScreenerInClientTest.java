package com.stockanalyzer.canslim;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenerInClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("sends the session cookie and the query in one request, with none of the parameters robots.txt rules out")
    void oneRequestWithoutPagingParameters() throws IOException {
        List<String> cookies = new CopyOnWriteArrayList<>();
        List<String> queries = new CopyOnWriteArrayList<>();
        start(exchange -> {
            cookies.add(exchange.getRequestHeaders().getFirst("Cookie"));
            queries.add(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            respond(exchange, 200, resultsPage(1, 1, "AAA"));
        });

        ScreenerClient.Result result = client("abc123").screen("Return on equity > 17");

        assertEquals(List.of("AAA"), result.stocks().stream().map(ScreenedStock::symbol).toList());
        assertFalse(result.moreAvailable());
        assertEquals(List.of("sessionid=abc123"), cookies, "one request");
        assertEquals(List.of("query=Return on equity > 17"), queries,
                "the query alone: robots.txt disallows page, limit, sort and q");
    }

    @Test
    @DisplayName("a result with more pages reads only the first and says there is more")
    void readsOnlyTheFirstPage() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        start(exchange -> {
            int n = requests.incrementAndGet();
            respond(exchange, 200, resultsPage(n, 9, "S" + n));
        });

        ScreenerClient.Result result = client("abc").screen("x > 1");

        assertEquals(1, requests.get());
        assertEquals(List.of("S1"), result.stocks().stream().map(ScreenedStock::symbol).toList());
        assertTrue(result.moreAvailable());
    }

    @Test
    @DisplayName("a redirect to the login page means the session needs renewing")
    void expiredSession() throws IOException {
        start(exchange -> {
            exchange.getResponseHeaders().add("Location", "/register/?next=/screen/raw/");
            respond(exchange, 302, "");
        });

        ScreenerException e = assertThrows(ScreenerException.class, () -> client("old").screen("x > 1"));
        assertTrue(e.needsLogin());
        assertTrue(e.getMessage().contains("SCREENER_SESSION_ID"));
    }

    @Test
    @DisplayName("a client without a session says so, so a caller can skip the query")
    void hasSession() {
        assertTrue(!new ScreenerInClient(HttpClient.newHttpClient(), "http://localhost:1", null).hasSession());
        assertTrue(!new ScreenerInClient(HttpClient.newHttpClient(), "http://localhost:1", "  ").hasSession());
        assertTrue(new ScreenerInClient(HttpClient.newHttpClient(), "http://localhost:1", "abc").hasSession());
    }

    @Test
    @DisplayName("no session configured fails before any request, saying how to get one")
    void noSession() {
        ScreenerInClient client = new ScreenerInClient(HttpClient.newHttpClient(), "http://localhost:1",
                null);
        ScreenerException e = assertThrows(ScreenerException.class, () -> client.screen("x > 1"));
        assertTrue(e.needsLogin());
    }

    @Test
    @DisplayName("a page with an error and no table surfaces screener's message")
    void rejectedQuery() throws IOException {
        start(exchange -> respond(exchange, 200,
                "<html><div class=\"flash-error\">Unknown ratio: Profit growth 9Years</div></html>"));

        ScreenerException e = assertThrows(ScreenerException.class, () -> client("abc").screen("bad"));
        assertTrue(e.getMessage().contains("Unknown ratio"), e.getMessage());
        assertTrue(!e.needsLogin());
    }

    private ScreenerInClient client(String session) {
        return new ScreenerInClient(HttpClient.newHttpClient(),
                "http://localhost:" + server.getAddress().getPort(), session);
    }

    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/screen/raw/", handler::handle);
        server.start();
    }

    private static String resultsPage(int page, int of, String symbol) {
        return """
                <span>Showing page %d of %d</span>
                <table class="data-table striped"><tbody>
                <tr><th class="text">S.No.</th><th class="text">Company</th>
                    <th data-tooltip="Return on equity">ROE</th></tr>
                <tr data-row-company-id="1"><td>1.</td>
                    <td><a href="/company/%s/consolidated/">%s Ltd</a></td><td>21.5</td></tr>
                </tbody></table>""".formatted(page, of, symbol, symbol);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }
}
