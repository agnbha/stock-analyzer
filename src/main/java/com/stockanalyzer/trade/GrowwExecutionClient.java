package com.stockanalyzer.trade;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockanalyzer.auth.GrowwAuthenticator;
import com.stockanalyzer.client.GrowwApiException;
import com.stockanalyzer.client.RateLimiter;
import com.stockanalyzer.model.Product;
import com.stockanalyzer.model.Side;
import com.stockanalyzer.model.Trade;
import com.stockanalyzer.util.JsonMapper;
import com.stockanalyzer.util.MarketClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the broker's fills.
 *
 * <p>It takes two calls, because Groww models orders and fills separately:
 * {@code GET /v1/order/list} returns the order book, and
 * {@code GET /v1/order/trades/{growwOrderId}} returns the fills for one order.
 * The fill is what the journal wants - one order can fill in several trades at
 * different prices, and {@code groww_trade_id} is the natural key that makes
 * re-importing idempotent.
 *
 * <p><b>The order list only ever returns the current day.</b> There is no date
 * parameter and no second page of history, so trades from before today cannot
 * be fetched here at all. That is why the journal is built up by importing
 * every evening; for anything older, use the CSV importer against a contract
 * note. Asking for an earlier range logs a warning rather than silently
 * returning nothing, which is exactly how this went unnoticed before.
 *
 * <p>Fills carry no charges, so {@link TradeJournalService} applies the
 * configured {@link ChargeModel} to them.
 */
public final class GrowwExecutionClient implements ExecutionDataClient {

    private static final Logger log = LoggerFactory.getLogger(GrowwExecutionClient.class);
    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES = 20;

    private final HttpClient httpClient;
    private final GrowwAuthenticator authenticator;
    private final String baseUrl;
    private final MarketClock clock;
    private final Product defaultProduct;
    private final String segment;
    private final RateLimiter rateLimiter;

    public GrowwExecutionClient(HttpClient httpClient, GrowwAuthenticator authenticator, String baseUrl,
                                MarketClock clock, Product defaultProduct, String segment,
                                RateLimiter rateLimiter) {
        this.httpClient = httpClient;
        this.authenticator = authenticator;
        this.baseUrl = baseUrl;
        this.clock = clock;
        this.defaultProduct = defaultProduct;
        this.segment = segment;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public List<Trade> fetchTrades(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock.zone());
        if (from.isBefore(today)) {
            log.warn("The broker's order list only serves today ({}). Trades from {} to {} cannot be "
                            + "fetched from the API - import those from a contract note CSV instead.",
                    today, from, today.minusDays(1).isBefore(from) ? from : today.minusDays(1));
        }

        List<JsonNode> orders = fetchOrders();
        if (orders.isEmpty()) {
            log.info("No orders in the broker's order book today");
            return List.of();
        }

        List<Trade> trades = new ArrayList<>();
        int skippedUnfilled = 0;
        for (JsonNode order : orders) {
            if (order.path("filled_quantity").asInt(0) <= 0) {
                skippedUnfilled++;
                continue;
            }
            for (JsonNode fill : fetchFills(order.path("groww_order_id").asText())) {
                Trade trade = toTrade(fill);
                if (trade != null && !trade.sessionDate().isBefore(from) && !trade.sessionDate().isAfter(to)) {
                    trades.add(trade);
                }
            }
        }
        log.info("Broker order book: {} orders ({} unfilled), {} fills in range",
                orders.size(), skippedUnfilled, trades.size());
        return trades;
    }

    private List<JsonNode> fetchOrders() {
        List<JsonNode> orders = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode payload = get("/order/list?page=" + page + "&page_size=" + PAGE_SIZE
                    + "&segment=" + segment, "order list");
            JsonNode list = payload == null ? null : payload.path("order_list");
            if (list == null || !list.isArray() || list.isEmpty()) {
                break;
            }
            list.forEach(orders::add);
            if (list.size() < PAGE_SIZE) {
                break;
            }
        }
        return orders;
    }

    private List<JsonNode> fetchFills(String growwOrderId) {
        JsonNode payload = get("/order/trades/" + growwOrderId + "?page=0&page_size=" + PAGE_SIZE
                + "&segment=" + segment, "fills for " + growwOrderId);
        JsonNode list = payload == null ? null : payload.path("trade_list");
        if (list == null || !list.isArray()) {
            return List.of();
        }
        List<JsonNode> fills = new ArrayList<>(list.size());
        list.forEach(fills::add);
        return fills;
    }

    private JsonNode get(String path, String what) {
        rateLimiter.acquire();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + authenticator.getAccessToken())
                .header("X-API-VERSION", "1.0")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new GrowwApiException("Failed to reach the Groww " + what + " endpoint", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new GrowwApiException("Groww " + what + " request failed with status "
                    + response.statusCode() + ": " + abbreviate(response.body()), response.statusCode());
        }
        try {
            JsonNode root = JsonMapper.INSTANCE.readTree(response.body());
            if (!"SUCCESS".equalsIgnoreCase(root.path("status").asText())) {
                log.warn("Groww returned a non-success status for the {}: {}", what,
                        abbreviate(response.body()));
                return null;
            }
            return root.path("payload");
        } catch (Exception e) {
            throw new GrowwApiException("Unparseable " + what + " response: "
                    + abbreviate(response.body()), e);
        }
    }

    /** Maps one fill; returns null when it is not something the journal should record. */
    private Trade toTrade(JsonNode fill) {
        String status = fill.path("trade_status").asText("EXECUTED");
        if (!"EXECUTED".equalsIgnoreCase(status)) {
            return null;
        }
        int quantity = fill.path("quantity").asInt(0);
        double price = fill.path("price").asDouble(0);
        if (quantity <= 0 || price <= 0) {
            return null;
        }

        String symbol = fill.path("trading_symbol").asText().toUpperCase();
        Side side = Side.valueOf(fill.path("transaction_type").asText("BUY").toUpperCase());
        Product product = productOf(fill.path("product").asText(null));
        long executedTs = timestampOf(fill);
        String tradeId = firstNonBlank(
                fill.path("groww_trade_id").asText(null),
                fill.path("exchange_trade_id").asText(null));
        if (tradeId == null) {
            tradeId = TradeIds.synthetic("groww", symbol, side, product, quantity, price, executedTs);
        }

        return new Trade(0, symbol, tradeId, fill.path("groww_order_id").asText(null),
                clock.sessionDateOf(executedTs), executedTs, side, quantity, price, product,
                0, null, Trade.ChargesSource.MODELLED, Trade.TradeSource.BROKER,
                fill.path("remark").asText(null));
    }

    private Product productOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return defaultProduct;
        }
        return switch (raw.trim().toUpperCase()) {
            case "MIS", "INTRADAY" -> Product.MIS;
            case "CNC", "DELIVERY" -> Product.CNC;
            default -> defaultProduct;
        };
    }

    private long timestampOf(JsonNode fill) {
        for (String field : List.of("trade_date_time", "created_at", "exchange_time")) {
            String value = fill.path(field).asText(null);
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                return LocalDateTime.parse(value.replace(' ', 'T')).atZone(clock.zone()).toEpochSecond();
            } catch (Exception ignored) {
                // Try the next field rather than losing the fill over a format quirk.
            }
        }
        log.warn("Fill {} carried no parseable timestamp; dating it to today's open",
                fill.path("groww_trade_id").asText("?"));
        return clock.epochOf(LocalDate.now(clock.zone()), clock.sessionOpen());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && !"null".equals(value)) {
                return value;
            }
        }
        return null;
    }

    private static String abbreviate(String body) {
        return body.length() > 400 ? body.substring(0, 400) + "..." : body;
    }
}
