package com.stockanalyzer.trade;

import com.stockanalyzer.model.Product;
import com.stockanalyzer.model.Side;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Deterministic ids for trades the broker did not number.
 *
 * <p>The same fill imported twice - from a re-exported CSV, or typed in again -
 * has to collapse to one row, so the id is derived from the fill itself rather
 * than generated.
 */
public final class TradeIds {

    private TradeIds() {
    }

    public static String synthetic(String source, String symbol, Side side, Product product,
                                   int quantity, double price, long executedTs) {
        String seed = String.join("|", source, symbol, side.name(), product.name(),
                String.valueOf(quantity), String.format("%.4f", price), String.valueOf(executedTs));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            return source.toLowerCase() + "-" + HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
