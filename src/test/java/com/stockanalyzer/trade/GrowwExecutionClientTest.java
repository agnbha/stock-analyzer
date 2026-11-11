package com.stockanalyzer.trade;

import com.stockanalyzer.client.RateLimiter;
import com.stockanalyzer.model.Product;
import com.stockanalyzer.model.Side;
import com.stockanalyzer.model.Trade;
import com.stockanalyzer.util.MarketClock;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Built from responses captured off the live API. The previous implementation
 * called an endpoint that did not exist and returned nothing forever; only a
 * test pinned to the real shape catches that.
 */
class GrowwExecutionClientTest {

    private static final MarketClock CLOCK = MarketClock.nse();

    /** A real order, with the fields trimmed to what the client reads. */
    private static final String ORDER_LIST = """
            {"status":"SUCCESS","payload":{"order_list":[
              {"groww_order_id":"GLT260901093022Y8OWU6WTDKFH","trading_symbol":"JPPOWER",
               "order_status":"EXECUTED","quantity":2000,"filled_quantity":2000,
               "average_fill_price":16.82,"exchange":"NSE","transaction_type":"BUY",
               "segment":"CASH","product":"CNC","created_at":"2026-09-01T09:30:22.98",
               "exchange_time":"2026-09-01T09:30:22","trade_date":"2026-09-01T00:00:00"}
            ]}}""";

    private static final String TRADE_LIST = """
            {"status":"SUCCESS","payload":{"trade_list":[
              {"price":16.82,"quantity":2000,"groww_order_id":"GLT260901093022Y8OWU6WTDKFH",
               "groww_trade_id":"GLT260901093022Y8OWU6WTDKFHT201123850",
               "exchange_trade_id":"201123850","trade_status":"EXECUTED",
               "trading_symbol":"JPPOWER","remark":null,"exchange":"NSE","segment":"CASH",
               "product":"CNC","transaction_type":"BUY","created_at":"2026-09-01T09:30:22",
               "trade_date_time":"2026-09-01T09:30:22","settlement_number":null}
            ]}}""";

    private HttpServer server;
    private final List<String> requested = new ArrayList<>();
    private final AtomicInteger tokensSpent = new AtomicInteger();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private GrowwExecutionClient client(String orderList, String tradeList) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/order/list", exchange -> {
            requested.add("order/list");
            respond(exchange, orderList);
        });
        server.createContext("/order/trades/", exchange -> {
            requested.add("order/trades");
            respond(exchange, tradeList);
        });
        server.start();

        RateLimiter limiter = tokensSpent::incrementAndGet;
        return new GrowwExecutionClient(HttpClient.newHttpClient(), () -> "token",
                "http://localhost:" + server.getAddress().getPort(), CLOCK, Product.MIS, "CASH", limiter);
    }

    @Test
    @DisplayName("walks the order book, then each order's fills")
    void mapsARealFill() throws IOException {
        List<Trade> trades = client(ORDER_LIST, TRADE_LIST)
                .fetchTrades(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1));

        assertEquals(1, trades.size());
        Trade trade = trades.getFirst();
        assertEquals("JPPOWER", trade.symbol());
        assertEquals(Side.BUY, trade.side());
        assertEquals(Product.CNC, trade.product());
        assertEquals(2000, trade.quantity());
        assertEquals(16.82, trade.price(), 0.0001);
        assertEquals(LocalDate.of(2026, 9, 1), trade.sessionDate());
        assertEquals("GLT260901093022Y8OWU6WTDKFHT201123850", trade.brokerTradeId(),
                "the fill id is the natural key, so re-importing is a no-op");
        assertEquals("GLT260901093022Y8OWU6WTDKFH", trade.orderId());
        assertEquals(Trade.TradeSource.BROKER, trade.source());
        assertEquals(List.of("order/list", "order/trades"), requested, "both steps are needed");
    }

    @Test
    @DisplayName("every request spends a rate-limit token")
    void everyRequestIsThrottled() throws IOException {
        client(ORDER_LIST, TRADE_LIST).fetchTrades(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1));

        assertEquals(2, tokensSpent.get());
    }

    @Test
    @DisplayName("an unfilled order is never asked for its fills")
    void skipsUnfilledOrders() throws IOException {
        String unfilled = ORDER_LIST.replace("\"filled_quantity\":2000", "\"filled_quantity\":0");

        assertTrue(client(unfilled, TRADE_LIST)
                .fetchTrades(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1)).isEmpty());
        assertEquals(List.of("order/list"), requested, "no point fetching fills for an unfilled order");
    }

    @Test
    @DisplayName("a fill that did not execute is not a trade")
    void skipsNonExecutedFills() throws IOException {
        String rejected = TRADE_LIST.replace("\"trade_status\":\"EXECUTED\"", "\"trade_status\":\"REJECTED\"");

        assertTrue(client(ORDER_LIST, rejected)
                .fetchTrades(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1)).isEmpty());
    }

    @Test
    @DisplayName("fills outside the requested range are filtered out")
    void filtersByDateRange() throws IOException {
        assertTrue(client(ORDER_LIST, TRADE_LIST)
                .fetchTrades(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)).isEmpty(),
                "the API only serves today, and today is not in that range");
    }

    @Test
    @DisplayName("an empty order book is not an error")
    void emptyOrderBook() throws IOException {
        List<Trade> trades = client("{\"status\":\"SUCCESS\",\"payload\":{\"order_list\":[]}}", TRADE_LIST)
                .fetchTrades(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1));

        assertTrue(trades.isEmpty());
        assertEquals(List.of("order/list"), requested);
    }

    private static final String BIG_ORDER = """
            {"status":"SUCCESS","payload":{"order_list":[
              {"groww_order_id":"GMK260903125400BIGSELL","trading_symbol":"BIGCO",
               "order_status":"EXECUTED","quantity":120,"filled_quantity":120,
               "exchange":"NSE","transaction_type":"SELL","segment":"CASH","product":"MIS",
               "trade_date":"2026-09-03T00:00:00"}
            ]}}""";

    /** A page of {@code count} one-share fills of the big order, numbered from {@code first}. */
    private static String fills(int first, int count) {
        StringBuilder list = new StringBuilder();
        for (int n = first; n < first + count; n++) {
            list.append(n == first ? "" : ",").append("""
                    {"price":100.5,"quantity":1,"groww_order_id":"GMK260903125400BIGSELL",
                     "groww_trade_id":"GMK260903125400BIGSELLT%d","exchange_trade_id":"%d",
                     "trade_status":"EXECUTED","trading_symbol":"BIGCO","exchange":"NSE","segment":"CASH",
                     "product":"MIS","transaction_type":"SELL","trade_date_time":"2026-09-03T12:54:00"}
                    """.formatted(n, n));
        }
        return "{\"status\":\"SUCCESS\",\"payload\":{\"trade_list\":[" + list + "]}}";
    }

    /** A server whose fills come page by page: {@code pages} maps the page number to its body (empty past the end). */
    private GrowwExecutionClient clientWithPages(String orderList, Map<Integer, String> pages) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/order/list", exchange -> respond(exchange, orderList));
        server.createContext("/order/trades/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            int page = Integer.parseInt(query.replaceAll(".*page=(\\d+).*", "$1"));
            requested.add("order/trades?page=" + page);
            respond(exchange, pages.getOrDefault(page, "{\"status\":\"SUCCESS\",\"payload\":{\"trade_list\":[]}}"));
        });
        server.start();
        return new GrowwExecutionClient(HttpClient.newHttpClient(), () -> "token",
                "http://localhost:" + server.getAddress().getPort(), CLOCK, Product.MIS, "CASH", tokensSpent::incrementAndGet);
    }

    private static final LocalDate THE_DAY = LocalDate.of(2026, 9, 3);

    @Test
    @DisplayName("an order the exchange split into more fills than one page holds is read to the end")
    void readsEveryPageOfFills() throws IOException {
        List<Trade> trades = clientWithPages(BIG_ORDER, Map.of(0, fills(1, 50), 1, fills(51, 50), 2, fills(101, 20)))
                .fetchTrades(THE_DAY, THE_DAY);

        assertEquals(120, trades.size(), "all the fills, not the first page's 50");
        assertEquals(120, trades.stream().mapToInt(Trade::quantity).sum(), "the order's whole quantity");
        assertEquals(120, trades.stream().map(Trade::brokerTradeId).distinct().count(), "each fill once");
        assertEquals(List.of("order/trades?page=0", "order/trades?page=1", "order/trades?page=2"), requested);
    }

    @Test
    @DisplayName("an order of exactly one full page asks for the next page, finds it empty and stops")
    void aFullPageIsFollowedByAnEmptyOne() throws IOException {
        List<Trade> trades = clientWithPages(BIG_ORDER, Map.of(0, fills(1, 50))).fetchTrades(THE_DAY, THE_DAY);

        assertEquals(50, trades.size());
        assertEquals(List.of("order/trades?page=0", "order/trades?page=1"), requested);
    }

    @Test
    @DisplayName("a short first page is the whole order: no second request")
    void aShortPageIsTheLastOne() throws IOException {
        List<Trade> trades = clientWithPages(BIG_ORDER, Map.of(0, fills(1, 49))).fetchTrades(THE_DAY, THE_DAY);

        assertEquals(49, trades.size());
        assertEquals(List.of("order/trades?page=0"), requested);
    }

    @Test
    @DisplayName("a server that ignores the page number and repeats the first page does not duplicate fills or loop")
    void aRepeatedPageIsNotReadTwice() throws IOException {
        String page = fills(1, 50);
        List<Trade> trades = clientWithPages(BIG_ORDER, Map.of(0, page, 1, page, 2, page, 3, page))
                .fetchTrades(THE_DAY, THE_DAY);

        assertEquals(50, trades.size(), "each fill once");
        assertEquals(List.of("order/trades?page=0", "order/trades?page=1"), requested, "stops at the first repeat");
    }

    @Test
    @DisplayName("fills that do not add up to the quantity the order filled are reported, with the gap")
    void aShortfallIsReported() {
        assertEquals(null, GrowwExecutionClient.shortfall("ORD1", "BIGCO", 120, 120), "complete: nothing to say");
        String message = GrowwExecutionClient.shortfall("ORD1", "BIGCO", 120, 50);
        assertTrue(message.contains("ORD1") && message.contains("BIGCO") && message.contains("filled 120")
                && message.contains("add up to 50") && message.contains("off by 70"), message);
    }

    @Test
    @DisplayName("a server with no end to its pages is read up to the page limit and no further")
    void thereIsALimitToThePages() throws IOException {
        Map<Integer, String> endless = new java.util.HashMap<>();
        for (int page = 0; page < 40; page++) {
            endless.put(page, fills(page * 50 + 1, 50));
        }

        List<Trade> trades = clientWithPages(BIG_ORDER, endless).fetchTrades(THE_DAY, THE_DAY);

        assertEquals(20 * 50, trades.size());
        assertEquals(20, requested.size(), "twenty pages, then it stops");
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        try (var out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
