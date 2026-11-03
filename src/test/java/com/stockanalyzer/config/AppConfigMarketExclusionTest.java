package com.stockanalyzer.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppConfigMarketExclusionTest {

    @Test
    @DisplayName("the symbols Groww's candle API rejects are excluded from the market scan out of the box")
    void shippedExclusions() {
        // An environment variable of the same name would outrank the file; a developer's shell has none.
        Set<String> excluded = AppConfig.load().canSlimMarketExcluded();

        assertEquals(Set.of("UNIVAFOODS-BE", "ASUTENT", "BENTCOM", "MEENST", "PUNCTRD", "SHYAM", "TERRAREAL",
                "TERRAFORM", "ZNILKENG", "ZMANSOON", "ZNIVITRD"), excluded);
    }
}
