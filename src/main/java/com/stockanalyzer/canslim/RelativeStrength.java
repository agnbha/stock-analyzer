package com.stockanalyzer.canslim;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The "L" of CAN SLIM as a 1-99 percentile, like IBD's RS rating: 99 means the
 * stock's weighted return beat every other stock ranked alongside it.
 *
 * <p>A percentile is only as meaningful as the field it is ranked against.
 * IBD ranks against every listed stock; here the field is whatever was priced
 * in the same scan - the screened candidates, the configured universe and any
 * holdings. Candidates have already passed an earnings screen, so the field is
 * stronger than the market as a whole, and an 80 here is a harder bar than an
 * 80 in IBD's tables.
 */
public final class RelativeStrength {

    private RelativeStrength() {
    }

    /** Symbols without a weighted return, and any field of fewer than two, get no rating. */
    public static Map<String, Integer> ratings(Map<String, Double> weightedReturns) {
        List<Map.Entry<String, Double>> ranked = weightedReturns.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .toList();
        Map<String, Integer> ratings = new HashMap<>();
        int n = ranked.size();
        if (n < 2) {
            return ratings;
        }
        for (Map.Entry<String, Double> entry : ranked) {
            long beaten = ranked.stream().filter(other -> other.getValue() < entry.getValue()).count();
            ratings.put(entry.getKey(), (int) Math.round(1 + 98.0 * beaten / (n - 1)));
        }
        return ratings;
    }
}
