package com.stockanalyzer.alert;

import com.stockanalyzer.store.WatchlistRepository;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns the {@code alerts.symbols} setting into the set of symbols that may alert.
 *
 * <p>The kinds add up: {@code bullish,holdings} is every bullish symbol plus
 * everything held. {@code all} switches the filter off.
 */
public final class AlertScopeResolver {

    public static final String ALL = "all";
    public static final String BULLISH = "bullish";
    public static final String HOLDINGS = "holdings";

    private final WatchlistRepository repository;

    public AlertScopeResolver(WatchlistRepository repository) {
        this.repository = repository;
    }

    public AlertScope resolve(LocalDate sessionDate, List<String> kinds) {
        Set<String> symbols = new HashSet<>();
        for (String kind : kinds) {
            switch (kind.toLowerCase(Locale.ROOT)) {
                case ALL -> {
                    return AlertScope.everything();
                }
                case BULLISH -> symbols.addAll(repository.bullishSymbols(sessionDate));
                case HOLDINGS -> symbols.addAll(repository.heldSymbols());
                default -> throw new IllegalStateException("Unknown alerts.symbols kind '" + kind
                        + "'; use any of " + String.join(", ", ALL, BULLISH, HOLDINGS));
            }
        }
        return AlertScope.of(symbols);
    }
}
