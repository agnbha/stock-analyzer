package com.stockanalyzer.alert;

import java.util.Set;
import java.util.TreeSet;

/**
 * Which symbols may raise an alert. Market-wide alerts (no symbol) always pass.
 *
 * <p>Narrowing the scope does not narrow what is tracked: events, candles and the
 * dashboards still cover every symbol. It only decides what is allowed to
 * interrupt, and it is applied before the policy counts anything, so a symbol
 * outside the scope never uses up the day's cap.
 */
public final class AlertScope {

    private static final AlertScope EVERYTHING = new AlertScope(null);

    private final Set<String> symbols;

    private AlertScope(Set<String> symbols) {
        this.symbols = symbols;
    }

    public static AlertScope everything() {
        return EVERYTHING;
    }

    public static AlertScope of(Set<String> symbols) {
        return new AlertScope(Set.copyOf(symbols));
    }

    public boolean includes(String symbol) {
        return symbols == null || symbol == null || symbols.contains(symbol);
    }

    public boolean isEverything() {
        return symbols == null;
    }

    /** The symbols in scope, sorted; empty for {@link #everything()}, which has no list. */
    public Set<String> symbols() {
        return symbols == null ? Set.of() : new TreeSet<>(symbols);
    }
}
