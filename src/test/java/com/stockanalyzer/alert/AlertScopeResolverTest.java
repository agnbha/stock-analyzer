package com.stockanalyzer.alert;

import com.stockanalyzer.store.WatchlistRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertScopeResolverTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 29);

    private final List<LocalDate> bullishAskedFor = new ArrayList<>();

    private final AlertScopeResolver resolver = new AlertScopeResolver(new WatchlistRepository() {
        @Override
        public Set<String> bullishSymbols(LocalDate before) {
            bullishAskedFor.add(before);
            return Set.of("DRREDDY", "SHREECEM");
        }

        @Override
        public Set<String> heldSymbols() {
            return Set.of("NIFTYBEES");
        }
    });

    @Test
    @DisplayName("bullish and holdings add up")
    void kindsAddUp() {
        AlertScope scope = resolver.resolve(DAY, List.of("bullish", "holdings"));

        assertEquals(Set.of("DRREDDY", "NIFTYBEES", "SHREECEM"), scope.symbols());
        assertTrue(scope.includes("NIFTYBEES"));
        assertFalse(scope.includes("TCS"));
    }

    @Test
    @DisplayName("bullish alone leaves out what is merely held")
    void bullishAlone() {
        AlertScope scope = resolver.resolve(DAY, List.of("bullish"));

        assertTrue(scope.includes("DRREDDY"));
        assertFalse(scope.includes("NIFTYBEES"));
    }

    @Test
    @DisplayName("the bullish test is run as of the session, so a restart cannot change the set")
    void bullishIsAsOfTheSession() {
        resolver.resolve(DAY, List.of("bullish"));

        assertEquals(List.of(DAY), bullishAskedFor);
    }

    @Test
    @DisplayName("'all' switches the filter off, wherever it appears")
    void allMeansEverything() {
        assertTrue(resolver.resolve(DAY, List.of("holdings", "all")).isEverything());
        assertTrue(resolver.resolve(DAY, List.of("ALL")).includes("ANYTHING"));
    }

    @Test
    @DisplayName("kinds are not case sensitive")
    void caseInsensitive() {
        assertTrue(resolver.resolve(DAY, List.of("Bullish")).includes("DRREDDY"));
    }

    @Test
    @DisplayName("a mistyped kind stops the daemon rather than silently alerting on nothing")
    void unknownKindFails() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> resolver.resolve(DAY, List.of("bulish")));

        assertTrue(failure.getMessage().contains("bulish"), failure.getMessage());
        assertTrue(failure.getMessage().contains("bullish"), failure.getMessage());
    }

    @Test
    @DisplayName("market-wide alerts have no symbol and are always in scope")
    void marketWideAlwaysIn() {
        assertTrue(resolver.resolve(DAY, List.of("holdings")).includes(null));
    }
}
