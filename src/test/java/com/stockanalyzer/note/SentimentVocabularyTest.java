package com.stockanalyzer.note;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SentimentVocabularyTest {

    private final SentimentVocabulary vocabulary = SentimentVocabulary.defaults();

    @Test
    @DisplayName("home-made labels count as the generic sentiment they stand for")
    void aliases() {
        assertEquals("BULLISH", vocabulary.canonical("UP"));
        assertEquals("BULLISH", vocabulary.canonical(" up "));
        assertEquals("NEUTRAL", vocabulary.canonical("LEVEL"));
        assertEquals("BEARISH", vocabulary.canonical("down"));
        assertEquals("BULLISH", vocabulary.canonical("Bullish"));
    }

    @Test
    @DisplayName("a label outside the vocabulary is kept as itself, but is not a market view")
    void freeLabels() {
        assertEquals("NERVOUS", vocabulary.canonical("nervous"));
        assertFalse(vocabulary.isGeneric("NERVOUS"));
        assertTrue(vocabulary.isGeneric("UP"));
        assertNull(vocabulary.canonical("  "));
        assertNull(vocabulary.canonical(null));
    }

    @Test
    @DisplayName("the sentiment of a note is the first of its tags that is a generic one")
    void fromTags() {
        assertEquals("BULLISH", vocabulary.fromTags(List.of("WIPRO", "UP")));
        assertEquals("NEUTRAL", vocabulary.fromTags(List.of("NERVOUS", "LEVEL", "UP")));
        assertNull(vocabulary.fromTags(List.of("WIPRO", "BUY", "NERVOUS")));
        assertNull(vocabulary.fromTags(List.of()));
    }

    @Test
    @DisplayName("aliases are read from the setting, and a bad entry is ignored")
    void parsing() {
        assertEquals(Map.of("UP", "BULLISH", "DOWN", "BEARISH"),
                SentimentVocabulary.parseAliases("UP=BULLISH, DOWN=BEARISH ,oops,=x,y=").entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(e -> e.getKey().trim(), e -> e.getValue().trim())));
    }
}
