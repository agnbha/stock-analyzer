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
    @DisplayName("sends the session cookie and the query, and reads every page")
    void readsAllPages() throws IOException {
        List<String> cookies = new CopyOnWriteArrayList<>();
        List<String> queries = new CopyOnWriteArrayList<>();
        start(exchange -> {
            cookies.add(exchange.getRequestHeaders().getFirst("Cookie"));
            String raw = exchange.getRequestURI().getRawQuery();
            queries.add(URLDecoder.decode(raw, StandardCharsets.UTF_8));
            int page = raw.contains("page=2") ? 2 : 1;
            respond(exchange, 200, resultsPage(page, 2, page == 1 ? "AAA" : "BBB"));
        });

        List<ScreenedStock> stocks = client("abc123", 5).screen("Return on equity > 17");

        assertEquals(List.of("AAA", "BBB"), stocks.stream().map(ScreenedStock::symbol).toList());
        assertEquals(List.of("sessionid=abc123", "sessionid=abc123"), cookies);
        assertTrue(queries.getFirst().contains("query=Return on equity > 17"), queries.getFirst());
        assertTrue(queries.getFirst().contains("limit=50"));
    }

    @Test
    @DisplayName("stops at the page limit however many pages the result has")
    void respectsPageLimit() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        start(exchange -> {
            int n = requests.incrementAndGet();
            respond(exchange, 200, resultsPage(n, 9, "S" + n));
        });

        List<ScreenedStock> stocks = client("abc", 2).screen("x > 1");

        assertEquals(2, requests.get());
        assertEquals(2, stocks.size());
    }

    @Test
    @DisplayName("a redirect to the login page means the session needs renewing")
    void expiredSession() throws IOException {
        start(exchange -> {
            exchange.getResponseHeaders().add("Location", "/register/?next=/screen/raw/");
            respond(exchange, 302, "");
        });

        ScreenerException e = assertThrows(ScreenerException.class, () -> client("old", 3).screen("x > 1"));
        assertTrue(e.needsLogin());
        assertTrue(e.getMessage().contains("SCREENER_SESSION_ID"));
    }

    @Test
    @DisplayName("no session configured fails before any request, saying how to get one")
    void noSession() {
        ScreenerInClient client = new ScreenerInClient(HttpClient.newHttpClient(), "http://localhost:1",
                null, 3, 0);
        ScreenerException e = assertThrows(ScreenerException.class, () -> client.screen("x > 1"));
        assertTrue(e.needsLogin());
    }

    @Test
    @DisplayName("a page with an error and no table surfaces screener's message")
    void rejectedQuery() throws IOException {
        start(exchange -> respond(exchange, 200,
                "<html><div class=\"flash-error\">Unknown ratio: Profit growth 9Years</div></html>"));

        ScreenerException e = assertThrows(ScreenerException.class, () -> client("abc", 3).screen("bad"));
        assertTrue(e.getMessage().contains("Unknown ratio"), e.getMessage());
        assertTrue(!e.needsLogin());
    }

    private ScreenerInClient client(String session, int maxPages) {
        return new ScreenerInClient(HttpClient.newHttpClient(),
                "http://localhost:" + server.getAddress().getPort(), session, maxPages, 0);
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
