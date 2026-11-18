package com.stockanalyzer.markets;

import java.time.LocalDate;
import java.util.List;
import java.util.function.BiFunction;

/**
 * The series the page linked from CAN SLIM draws, each read from where it is published openly for programs, with no
 * key, under names of their own: the US Treasury publishes its yields itself; the EIA's Brent and WTI prices come
 * through the open "oil-prices" dataset.
 */
public final class MarketSources implements BiFunction<String, LocalDate, List<Observation>> {

    public static final String US_10Y = "US10Y";
    public static final String US_2Y = "US2Y";
    public static final String BRENT = "BRENT";
    public static final String WTI = "WTI";
    public static final List<String> ALL = List.of(US_10Y, US_2Y, BRENT, WTI);

    private final TreasuryYieldClient treasury;
    private final OilPricesClient oil;

    public MarketSources(TreasuryYieldClient treasury, OilPricesClient oil) {
        this.treasury = treasury;
        this.oil = oil;
    }

    @Override
    public List<Observation> apply(String series, LocalDate from) {
        return switch (series) {
            case US_10Y -> treasury.fetch("10 Yr", from);
            case US_2Y -> treasury.fetch("2 Yr", from);
            case BRENT -> oil.fetch("brent", from);
            case WTI -> oil.fetch("wti", from);
            default -> throw new MarketDataException("no source for a series called " + series, null);
        };
    }
}
