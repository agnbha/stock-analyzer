package com.stockanalyzer.note;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The dashboards that draw the notes given a symbol by hand (each has a "Saved notes" layer), by the short name
 * {@code chartnotes draw --on} takes: the dashboard's uid without its {@code stockanalyzer-} prefix.
 */
public enum NoteDashboard {
    OVERVIEW("stockanalyzer-overview", "Account Overview"),
    CANDLES("stockanalyzer-candles", "Candles and Calls"),
    REASONS("stockanalyzer-reasons", "Decision Reasons"),
    TREND("stockanalyzer-trend", "Stock Trend"),
    CANSLIM("stockanalyzer-canslim", "CAN SLIM"),
    MARKETS("stockanalyzer-markets", "Bonds and Commodities"),
    INSTITUTIONAL("stockanalyzer-institutional", "Institutional Investment"),
    MARKETWATCH("stockanalyzer-marketwatch", "Market Watch");

    private final String uid;
    private final String title;

    NoteDashboard(String uid, String title) {
        this.uid = uid;
        this.title = title;
    }

    public String uid() {
        return uid;
    }

    public String title() {
        return title;
    }

    public String shortName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Reads a comma-separated list of short names or uids, in any case, in the order given and without repeats.
     * {@code all} alone is every dashboard, which is to say no limit: it returns an empty list.
     *
     * @throws IllegalArgumentException for a name that is not one of these dashboards, naming the ones there are
     */
    public static List<NoteDashboard> parseList(String text) {
        if (text.trim().equalsIgnoreCase("all")) {
            return List.of();
        }
        List<NoteDashboard> found = new ArrayList<>();
        for (String part : text.split(",")) {
            String name = part.trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            NoteDashboard dashboard = Arrays.stream(values())
                    .filter(d -> d.shortName().equals(name) || d.uid.equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No dashboard '" + part.trim() + "': use "
                            + Arrays.stream(values()).map(NoteDashboard::shortName).collect(Collectors.joining(", "))
                            + ", or all"));
            if (!found.contains(dashboard)) {
                found.add(dashboard);
            }
        }
        if (found.isEmpty()) {
            throw new IllegalArgumentException("Name at least one dashboard, or all");
        }
        return found;
    }

    /** The title of the dashboard with this uid, or the uid itself for one that is not listed here. */
    public static String titleOf(String uid) {
        return Arrays.stream(values()).filter(d -> d.uid.equals(uid)).map(NoteDashboard::title).findFirst().orElse(uid);
    }
}
