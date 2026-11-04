package com.stockanalyzer.note;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The generic sentiments a note is counted under - BULLISH, BEARISH, NEUTRAL - and the home-made labels that
 * stand for them (UP, DOWN, LEVEL...), so a note tagged the way you happened to write it on the day is counted
 * with the rest. Labels outside the vocabulary are still allowed (NERVOUS, CALM); they just are not mistaken for
 * a market view when a sentiment is picked out of a note's tags.
 */
public final class SentimentVocabulary {

    private final Set<String> generic;
    private final Map<String, String> aliases;

    public SentimentVocabulary(Collection<String> generic, Map<String, String> aliases) {
        this.generic = new LinkedHashSet<>();
        generic.forEach(label -> this.generic.add(clean(label)));
        this.aliases = new LinkedHashMap<>();
        aliases.forEach((alias, label) -> this.aliases.put(clean(alias), clean(label)));
    }

    public static SentimentVocabulary defaults() {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (String bullish : List.of("UP", "UPTREND", "LONG")) {
            aliases.put(bullish, "BULLISH");
        }
        for (String bearish : List.of("DOWN", "DOWNTREND", "SHORT")) {
            aliases.put(bearish, "BEARISH");
        }
        for (String neutral : List.of("LEVEL", "FLAT", "SIDEWAYS", "RANGE")) {
            aliases.put(neutral, "NEUTRAL");
        }
        return new SentimentVocabulary(List.of("BULLISH", "BEARISH", "NEUTRAL"), aliases);
    }

    /** Parses {@code UP=BULLISH,DOWN=BEARISH}; an entry without an equals sign is ignored. */
    public static Map<String, String> parseAliases(String text) {
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String entry : text.split(",")) {
            String[] pair = entry.split("=", 2);
            if (pair.length == 2 && !pair[0].isBlank() && !pair[1].isBlank()) {
                parsed.put(pair[0], pair[1]);
            }
        }
        return parsed;
    }

    /** Upper-case, with anything that is not a letter or digit turned into an underscore; null when nothing is left. */
    public static String clean(String label) {
        if (label == null) {
            return null;
        }
        String code = label.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        return code.isEmpty() ? null : code;
    }

    /** The label in its generic form: UP becomes BULLISH, bullish becomes BULLISH, NERVOUS stays NERVOUS. */
    public String canonical(String label) {
        String code = clean(label);
        return code == null ? null : aliases.getOrDefault(code, code);
    }

    public boolean isGeneric(String label) {
        String code = canonical(label);
        return code != null && generic.contains(code);
    }

    /** The first tag that is a generic sentiment once aliases are applied, or null. */
    public String fromTags(List<String> tags) {
        for (String tag : tags) {
            if (isGeneric(tag)) {
                return canonical(tag);
            }
        }
        return null;
    }
}
