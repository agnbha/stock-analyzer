package com.stockanalyzer.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigMarketExclusionTest {

    @Test
    @DisplayName("the symbols Groww's candle API will not serve are excluded from the market scan out of the box")
    void shippedExclusions() {
        // An environment variable of the same name would outrank the file; a developer's shell has none.
        Set<String> excluded = AppConfig.load().canSlimMarketExcluded();

        // Both ends of each line of the list, so a lost continuation shows up; and one of each kind of
        // failure: a suffixed NSE name, a GA001 rejection, a symbol with no candles.
        assertTrue(excluded.containsAll(Set.of("UNIVAFOODS-BE", "ORTEL-BZ", "ADJIA", "CRAFTROOT", "EASYFIN",
                "SVAINDIA", "SYBLY", "ZSPEEDCO", "HVL", "ASUTENT")), excluded.toString());
        assertTrue(excluded.stream().allMatch(symbol -> symbol.matches("[A-Z0-9&-]+")), excluded.toString());
    }
}
