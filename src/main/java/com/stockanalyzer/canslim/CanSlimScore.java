package com.stockanalyzer.canslim;

import java.util.Map;

/**
 * One stock's CAN SLIM reading from one scan.
 *
 * @param source     why it was evaluated: it passed the screen, it is held, it is in the configured universe,
 *                   or it is one of the market's listed companies
 * @param priceAction null when no daily bars could be fetched
 * @param buyPoint   the price the sell rules measure from: the holding's cost, else the latest breakout's pivot
 * @param ratios     every ratio the screener returned for it, for the record
 */
public record CanSlimScore(
        String symbol,
        Source source,
        Letters letters,
        Double qtrProfitGrowth,
        Double qtrSalesGrowth,
        Double profitGrowth3y,
        Double roe,
        Double institutionalChange,
        Integer rsRating,
        PriceAction priceAction,
        Double buyPoint,
        CanSlimSignal signal,
        String reason,
        Map<String, Double> ratios) {

    public enum Source {
        SCREENER, HOLDING, UNIVERSE, MARKET
    }

    /**
     * Each letter: true passed, false failed, null unknown (the data was not
     * available, which is different from failing).
     */
    public record Letters(Boolean c, Boolean a, Boolean n, Boolean s, Boolean l, Boolean i, Boolean m) {

        public int passed() {
            int count = 0;
            for (Boolean letter : new Boolean[]{c, a, n, s, l, i, m}) {
                if (Boolean.TRUE.equals(letter)) {
                    count++;
                }
            }
            return count;
        }

        /** "CAN SLIM" with each failed letter as '-' and each unknown as '?': "CA-S?IM". */
        public String display() {
            Boolean[] values = {c, a, n, s, l, i, m};
            char[] names = {'C', 'A', 'N', 'S', 'L', 'I', 'M'};
            StringBuilder text = new StringBuilder();
            for (int k = 0; k < values.length; k++) {
                text.append(values[k] == null ? '?' : values[k] ? names[k] : '-');
            }
            return text.toString();
        }
    }
}
