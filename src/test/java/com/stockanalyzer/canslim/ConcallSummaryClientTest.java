package com.stockanalyzer.canslim;

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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading a concall summary signed in. The session values are invented. */
class ConcallSummaryClientTest {

    private HttpServer server;
    private final List<String> cookies = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ScreenerCompanyClient serve(int status, String location, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/concalls/summary/", exchange -> {
            cookies.add(String.valueOf(exchange.getRequestHeaders().getFirst("Cookie")));
            if (location != null) {
                exchange.getResponseHeaders().add("Location", location);
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
        return new ScreenerCompanyClient(HttpClient.newHttpClient(),
                "http://localhost:" + server.getAddress().getPort(), 0, 0);
    }

    @Test
    @DisplayName("the summary is read with the session cookie, from the address the page gave")
    void signedIn() throws IOException {
        String body = serve(200, null, "<article><h1>Takeaways</h1></article>")
                .concallSummary("/concalls/summary/42/", "invented-session");

        assertTrue(body.contains("Takeaways"));
        assertEquals(List.of("sessionid=invented-session"), cookies);
    }

    @Test
    @DisplayName("a redirect to the login page means the session has expired: say so, and ask for a new one")
    void expiredSession() throws IOException {
        ScreenerException refused = assertThrows(ScreenerException.class,
                () -> serve(302, "/login/?next=/concalls/summary/42/", "").concallSummary("/concalls/summary/42/", "old"));
        assertTrue(refused.needsLogin());
        assertTrue(refused.getMessage().contains("SCREENER_SESSION_ID"), refused.getMessage());
    }

    @Test
    @DisplayName("without a session nothing is asked")
    void noSession() throws IOException {
        ScreenerCompanyClient client = serve(200, null, "<article></article>");
        ScreenerException refused = assertThrows(ScreenerException.class,
                () -> client.concallSummary("/concalls/summary/42/", " "));
        assertTrue(refused.needsLogin());
        assertTrue(cookies.isEmpty());
    }
}
