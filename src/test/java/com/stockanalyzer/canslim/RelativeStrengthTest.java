package com.stockanalyzer.canslim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelativeStrengthTest {

    @Test
    @DisplayName("the best weighted return rates 99, the worst 1, the rest in between")
    void percentiles() {
        Map<String, Double> returns = new HashMap<>();
        returns.put("A", 40.0);
        returns.put("B", 10.0);
        returns.put("C", -5.0);
        returns.put("D", null);

        Map<String, Integer> ratings = RelativeStrength.ratings(returns);

        assertEquals(99, ratings.get("A"));
        assertEquals(50, ratings.get("B"));
        assertEquals(1, ratings.get("C"));
        assertFalse(ratings.containsKey("D"), "no return, no rating");
    }

    @Test
    @DisplayName("a field of one cannot be ranked")
    void single() {
        assertTrue(RelativeStrength.ratings(Map.of("A", 10.0)).isEmpty());
    }
}
