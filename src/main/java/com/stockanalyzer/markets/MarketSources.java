package com.stockanalyzer.markets;

import java.time.LocalDate;
import java.util.List;
import java.util.function.BiFunction;

/**
 * The four series the page linked from CAN SLIM draws, each read from where it is published without charge, under
 * names of their own: the Treasury publishes the yields itself and fresher than anyone; the oil prices come from the
 * EIA through FRED.
 */
public final class MarketSources implements BiFunction<String, LocalDate, List<Observation>> {

    public static final String US_10Y = "US10Y";
    public static final String US_2Y = "US2Y";
    public static final String BRENT = "BRENT";
    public static final String WTI = "WTI";
    public static final List<String> ALL = List.of(US_10Y, US_2Y, BRENT, WTI);

    private final TreasuryYieldClient treasury;
    private final FredClient fred;

    public MarketSources(TreasuryYieldClient treasury, FredClient fred) {
        this.treasury = treasury;
        this.fred = fred;
    }

    @Override
    public List<Observation> apply(String series, LocalDate from) {
        return switch (series) {
            case US_10Y -> treasury.fetch("10 Yr", from);
            case US_2Y -> treasury.fetch("2 Yr", from);
            case BRENT -> fred.fetch("DCOILBRENTEU", from);
            case WTI -> fred.fetch("DCOILWTICO", from);
            default -> throw new MarketDataException("no source for a series called " + series, null);
        };
    }
}
