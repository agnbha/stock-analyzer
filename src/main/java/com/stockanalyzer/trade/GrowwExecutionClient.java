package com.stockanalyzer.trade;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockanalyzer.auth.GrowwAuthenticator;
import com.stockanalyzer.client.GrowwApiException;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the broker's trade book.
 *
 * <p>Field names are read defensively, with fallbacks, because the trade book
 * response shape is less well documented than the candle endpoints - the same
 * caveat the README already records for historical data. If an import comes
 * back empty while the web trade book shows fills, log the raw response and
 * adjust the field names here; nothing else needs to change.
 */
public final class GrowwExecutionClient implements ExecutionDataClient {

    private static final Logger log = LoggerFactory.getLogger(GrowwExecutionClient.class);

    private final HttpClient httpClient;
    private final GrowwAuthenticator authenticator;
    private final String baseUrl;
    private final MarketClock clock;
    private final Product defaultProduct;

    public GrowwExecutionClient(HttpClient httpClient, GrowwAuthenticator authenticator, String baseUrl,
                                MarketClock clock, Product defaultProduct) {
        this.httpClient = httpClient;
        this.authenticator = authenticator;
        this.baseUrl = baseUrl;
        this.clock = clock;
        this.defaultProduct = defaultProduct;
    }

    @Override
    public List<Trade> fetchTrades(LocalDate from, LocalDate to) {
        List<Trade> trades = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            trades.addAll(fetchOneDay(date));
        }
        return trades;
    }

    private List<Trade> fetchOneDay(LocalDate date) {
        String url = baseUrl + "/order/trades?segment=CASH&trade_date=" + date;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + authenticator.getAccessToken())
                .header("X-API-VERSION", "1.0")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new GrowwApiException("Failed to reach the Groww trade book for " + date, e);
        }
        if (response.statusCode() == 404) {
            log.debug("No trade book for {}", date);
            return List.of();
        }
        if (response.statusCode() / 100 != 2) {
            throw new GrowwApiException("Trade book request for " + date + " failed with status "
                    + response.statusCode() + ": " + response.body(), response.statusCode());
        }

        try {
            JsonNode root = JsonMapper.INSTANCE.readTree(response.body());
            JsonNode payload = root.has("payload") ? root.get("payload") : root;
            JsonNode list = payload.isArray() ? payload
                    : payload.has("trades") ? payload.get("trades")
                    : payload.get("trade_list");
            if (list == null || !list.isArray()) {
                log.warn("Unexpected trade book shape for {}; import produced nothing. Raw: {}", date,
                        abbreviate(response.body()));
                return List.of();
            }
            List<Trade> trades = new ArrayList<>();
            for (JsonNode node : list) {
                trades.add(toTrade(node, date));
            }
            return trades;
        } catch (GrowwApiException e) {
            throw e;
        } catch (Exception e) {
            throw new GrowwApiException("Unparseable trade book response for " + date + ": "
                    + abbreviate(response.body()), e);
        }
    }

    private Trade toTrade(JsonNode node, LocalDate date) {
        String symbol = text(node, "trading_symbol", "symbol", "tradingsymbol").toUpperCase();
        Side side = Side.valueOf(text(node, "transaction_type", "side", "trade_type").toUpperCase());
        int quantity = (int) number(node, "quantity", "trade_quantity", "qty");
        double price = number(node, "price", "trade_price", "average_price");
        Product product = productOf(text(node, "product", "product_type"));
        long executedTs = timestampOf(node, date);
        double charges = number(node, "total_charges", "charges");
        String tradeId = text(node, "groww_trade_id", "trade_id", "exchange_trade_id");
        if (tradeId.isBlank()) {
            tradeId = TradeIds.synthetic("groww", symbol, side, product, quantity, price, executedTs);
        }

        return new Trade(0, symbol, tradeId, text(node, "groww_order_id", "order_id"),
                clock.sessionDateOf(executedTs), executedTs, side, quantity, price, product,
                charges, charges > 0 ? node.toString() : null,
                charges > 0 ? Trade.ChargesSource.BROKER : Trade.ChargesSource.MODELLED,
                Trade.TradeSource.BROKER, null);
    }

    private Product productOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return defaultProduct;
        }
        String normalised = raw.trim().toUpperCase();
        return switch (normalised) {
            case "MIS", "INTRADAY" -> Product.MIS;
            case "CNC", "DELIVERY" -> Product.CNC;
            default -> defaultProduct;
        };
    }

    private long timestampOf(JsonNode node, LocalDate fallbackDate) {
        for (String field : List.of("exchange_time", "trade_date_time", "created_at", "timestamp")) {
            if (node.hasNonNull(field)) {
                String value = node.get(field).asText();
                try {
                    if (value.matches("\\d+")) {
                        long epoch = Long.parseLong(value);
                        return epoch > 9_999_999_999L ? epoch / 1000 : epoch;
                    }
                    return java.time.LocalDateTime.parse(value.replace(' ', 'T'))
                            .atZone(clock.zone()).toEpochSecond();
                } catch (Exception ignored) {
                    // Try the next candidate field rather than failing the whole import.
                }
            }
        }
        return clock.epochOf(fallbackDate, clock.sessionOpen());
    }

    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node.hasNonNull(field)) {
                return node.get(field).asText();
            }
        }
        return "";
    }

    private static double number(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node.hasNonNull(field)) {
                return node.get(field).asDouble();
            }
        }
        return 0;
    }

    private static String abbreviate(String body) {
        return body.length() > 500 ? body.substring(0, 500) + "..." : body;
    }
}
