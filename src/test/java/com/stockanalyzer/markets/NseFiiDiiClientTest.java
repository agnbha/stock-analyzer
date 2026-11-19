package com.stockanalyzer.markets;

import com.stockanalyzer.markets.NseFiiDiiClient.Category;
import com.stockanalyzer.markets.NseFiiDiiClient.Figure;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** NSE's FII/DII cash-market figures, in the JSON its page loads. The figures are invented. */
class NseFiiDiiClientTest {

    private static final String LATEST = """
            [{"buyValue":"12000.50","category":"DII","date":"18-Nov-2026","netValue":"-1500.25","sellValue":"13500.75"},
             {"buyValue":"15000","category":"FII/FPI","date":"18-Nov-2026","netValue":"2,000.40","sellValue":"12999.60"}]
            """;

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("the latest day's purchases, sales and net for each side; asked for once whatever is wanted")
    void latestDay() throws IOException {
        AtomicInteger asked = new AtomicInteger();
        NseFiiDiiClient client = client(200, LATEST, asked);

        LocalDate day = LocalDate.of(2026, 11, 18);
        assertEquals(List.of(new Observation(day, -1500.25)), client.fetch(Category.DII, Figure.NET, day));
        assertEquals(List.of(new Observation(day, 12000.50)), client.fetch(Category.DII, Figure.BUY, day));
        assertEquals(List.of(new Observation(day, 2000.40)), client.fetch(Category.FII, Figure.NET, day),
                "a figure written with a thousands comma");
        assertEquals(List.of(new Observation(day, 12999.60)), client.fetch(Category.FII, Figure.SELL, day));
        assertEquals(List.of(), client.fetch(Category.FII, Figure.NET, day.plusDays(1)),
                "a day already held is not read as a new one");
        assertEquals(1, asked.get());
    }

    @Test
    @DisplayName("an error page, a refusal or a list without the side asked for is an error, not an empty day")
    void notTheFigures() throws IOException {
        assertThrows(MarketDataException.class, () -> NseFiiDiiClient.parse("<html>Access Denied</html>"));
        assertThrows(MarketDataException.class, () -> NseFiiDiiClient.parse("{\"error\":\"none\"}"));
        assertThrows(MarketDataException.class, () -> NseFiiDiiClient.parse(
                "[{\"category\":\"DII\",\"date\":\"18-Nov-2026\",\"buyValue\":\"-\",\"sellValue\":\"1\",\"netValue\":\"1\"}]"));

        NseFiiDiiClient onlyDii = client(200, "[" + LATEST.strip().substring(1).split("},")[0] + "}]",
                new AtomicInteger());
        assertThrows(MarketDataException.class,
                () -> onlyDii.fetch(Category.FII, Figure.NET, LocalDate.of(2026, 11, 1)));
        stop();
        NseFiiDiiClient refused = client(403, "denied", new AtomicInteger());
        assertThrows(MarketDataException.class,
                () -> refused.fetch(Category.DII, Figure.NET, LocalDate.of(2026, 11, 1)));
    }

    private NseFiiDiiClient client(int status, String body, AtomicInteger asked) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/fiidiiTradeReact", exchange -> {
            asked.incrementAndGet();
            assertEquals("stock-analyzer/1.0", exchange.getRequestHeaders().getFirst("User-Agent"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return new NseFiiDiiClient(HttpClient.newHttpClient(),
                "http://localhost:" + server.getAddress().getPort() + "/api/fiidiiTradeReact");
    }
}
