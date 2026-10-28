package com.stockanalyzer.canslim;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenerCompanyClientTest {

    private static final String PAGE = """
            <section id="quarters"><table>
            <tr><th></th><th>Jun 2025</th><th>Sep 2025</th><th>Dec 2025</th><th>Mar 2026</th><th>Jun 2026</th></tr>
            <tr><td>Net Profit&nbsp;+</td><td>100</td><td>110</td><td>120</td><td>130</td><td>150</td></tr>
            </table></section>""";

    private HttpServer server;
    private final List<String> requested = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ScreenerCompanyClient client() {
        return new ScreenerCompanyClient(HttpClient.newHttpClient(),
                "http://localhost:" + server.getAddress().getPort(), 0);
    }

    private void serve(java.util.function.Function<String, Integer> statusFor) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/company/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            requested.add(path);
            int status = statusFor.apply(path);
            respond(exchange, status, status == 200 ? PAGE : "");
        });
        server.start();
    }

    @Test
    @DisplayName("reads the consolidated page, encoding the symbol")
    void consolidatedFirst() throws IOException {
        serve(path -> 200);

        Optional<CompanyFundamentals> found = client().fetch("M&M");

        assertEquals(List.of("/company/M%26M/consolidated/"), requested);
        assertEquals(50.0, found.orElseThrow().ratios().get(ScreenerRatios.QTR_PROFIT_GROWTH), 1e-9);
    }

    @Test
    @DisplayName("falls back to the standalone page for a company with no consolidated one")
    void standaloneFallback() throws IOException {
        serve(path -> path.endsWith("/consolidated/") ? 404 : 200);

        assertTrue(client().fetch("SMALLCO").isPresent());
        assertEquals(List.of("/company/SMALLCO/consolidated/", "/company/SMALLCO/"), requested);
    }

    @Test
    @DisplayName("a symbol screener does not list is empty, not an error")
    void unknownSymbol() throws IOException {
        serve(path -> 404);

        assertFalse(client().fetch("NOPE").isPresent());
    }

    @Test
    @DisplayName("being rate limited is an error that says to stop, not an empty result")
    void rateLimited() throws IOException {
        serve(path -> 429);

        ScreenerException e = assertThrows(ScreenerException.class, () -> client().fetch("ITC"));
        assertTrue(e.getMessage().contains("429"));
        assertFalse(e.needsLogin());
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
