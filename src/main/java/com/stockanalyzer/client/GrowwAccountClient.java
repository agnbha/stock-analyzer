package com.stockanalyzer.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockanalyzer.auth.GrowwAuthenticator;
import com.stockanalyzer.model.AccountSnapshot;
import com.stockanalyzer.util.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Reads the balance from Groww, in up to three calls.
 *
 * <p>{@code GET /v1/margins/detail/user} is the credit balance:
 * {@code clear_cash} is the whole ledger cash and {@code net_margin_used} is
 * the part of it blocked against open positions, so the free cash is the
 * difference. {@code GET /v1/holdings/user} lists the demat holdings, which
 * carry quantity and average price but no market price, so
 * {@code GET /v1/live-data/ltp} marks them - batched, because that endpoint
 * takes many symbols per call and holdings are otherwise one request each.
 *
 * <p>Only the margin call is required. If holdings or quotes fail the snapshot
 * still carries real cash, with the holdings degraded rather than the whole
 * reading lost: an unpriced holding is valued at cost and counted in
 * {@link AccountSnapshot#unpricedHoldings()} so the approximation is visible
 * instead of silent.
 */
public final class GrowwAccountClient implements AccountDataClient {

    private static final Logger log = LoggerFactory.getLogger(GrowwAccountClient.class);

    /** Groww's LTP endpoint takes a batch; this keeps the query string sane. */
    private static final int LTP_BATCH = 50;

    private final HttpClient httpClient;
    private final GrowwAuthenticator authenticator;
    private final String baseUrl;
    private final String exchange;
    private final String segment;
    private final RateLimiter rateLimiter;

    public GrowwAccountClient(HttpClient httpClient, GrowwAuthenticator authenticator, String baseUrl,
                              String exchange, String segment, RateLimiter rateLimiter) {
        this.httpClient = httpClient;
        this.authenticator = authenticator;
        this.baseUrl = baseUrl;
        this.exchange = exchange;
        this.segment = segment;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public AccountSnapshot fetch() {
        JsonNode margin = get("/margins/detail/user", "margin detail");
        if (margin == null) {
            throw new GrowwApiException("Groww returned no margin detail; the account balance is unknown");
        }

        double cash = margin.path("clear_cash").asDouble(0);
        double marginUsed = margin.path("net_margin_used").asDouble(0);
        double collateral = margin.path("collateral_available").asDouble(0);

        List<AccountSnapshot.Holding> holdings = fetchHoldings();
        return new AccountSnapshot(cash, marginUsed, cash - marginUsed, collateral, holdings,
                Instant.now().getEpochSecond());
    }

    /** Holdings marked to market. Never throws: cash alone still beats no reading. */
    private List<AccountSnapshot.Holding> fetchHoldings() {
        JsonNode payload;
        try {
            payload = get("/holdings/user", "holdings");
        } catch (GrowwApiException e) {
            log.warn("Could not read holdings; the snapshot will carry cash only: {}", e.getMessage());
            return List.of();
        }
        JsonNode list = payload == null ? null : payload.path("holdings");
        if (list == null || !list.isArray() || list.isEmpty()) {
            return List.of();
        }

        List<AccountSnapshot.Holding> unpriced = new ArrayList<>(list.size());
        for (JsonNode holding : list) {
            String symbol = holding.path("trading_symbol").asText(null);
            double quantity = holding.path("quantity").asDouble(0);
            if (symbol == null || symbol.isBlank() || quantity <= 0) {
                continue;
            }
            unpriced.add(new AccountSnapshot.Holding(symbol.toUpperCase(),
                    holding.path("isin").asText(null), quantity,
                    holding.path("average_price").asDouble(0), null));
        }

        Map<String, Double> prices = fetchLastPrices(unpriced.stream().map(AccountSnapshot.Holding::symbol).toList());
        List<AccountSnapshot.Holding> priced = new ArrayList<>(unpriced.size());
        for (AccountSnapshot.Holding holding : unpriced) {
            priced.add(new AccountSnapshot.Holding(holding.symbol(), holding.isin(), holding.quantity(),
                    holding.averagePrice(), prices.get(holding.symbol())));
        }
        long missing = priced.stream().filter(h -> h.lastPrice() == null).count();
        if (missing > 0) {
            log.warn("{} of {} holdings had no quote and are valued at cost", missing, priced.size());
        }
        return priced;
    }

    /** Batched last-traded prices, keyed by symbol. Missing keys mean no quote. */
    private Map<String, Double> fetchLastPrices(List<String> symbols) {
        Map<String, Double> prices = new HashMap<>();
        String prefix = exchange + "_";
        for (int start = 0; start < symbols.size(); start += LTP_BATCH) {
            List<String> batch = symbols.subList(start, Math.min(start + LTP_BATCH, symbols.size()));
            StringJoiner joined = new StringJoiner(",");
            batch.forEach(symbol -> joined.add(prefix + symbol));

            JsonNode payload;
            try {
                payload = get("/live-data/ltp?segment=" + segment + "&exchange_symbols=" + joined,
                        "last traded prices");
            } catch (GrowwApiException e) {
                // A quote subscription the account does not have, or a transient
                // failure. Either way the holdings fall back to cost basis.
                log.warn("Could not read last traded prices for {} symbols: {}", batch.size(), e.getMessage());
                continue;
            }
            if (payload == null) {
                continue;
            }
            payload.fields().forEachRemaining(entry -> {
                String symbol = entry.getKey().startsWith(prefix)
                        ? entry.getKey().substring(prefix.length())
                        : entry.getKey();
                if (entry.getValue().isNumber() && entry.getValue().asDouble() > 0) {
                    prices.put(symbol, entry.getValue().asDouble());
                }
            });
        }
        return prices;
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

    private static String abbreviate(String body) {
        if (body == null) {
            return "<empty>";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "...";
    }
}
