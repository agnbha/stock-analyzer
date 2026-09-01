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

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        try (var out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
