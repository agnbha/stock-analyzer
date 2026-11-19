package com.stockanalyzer.markets;

import java.time.LocalDate;
import java.util.List;
import java.util.function.BiFunction;

/**
 * The series the Bonds and Commodities page draws. The US Treasury publishes its yields itself; the EIA's Brent and WTI
 * prices come through the open "oil-prices" dataset; MCX's crude oil, gold and silver come from Groww's candles of
 * their futures, read with the app's own Groww login.
 */
public final class MarketSources implements BiFunction<String, LocalDate, List<Observation>> {

    public static final String US_10Y = "US10Y";
    public static final String US_2Y = "US2Y";
    public static final String BRENT = "BRENT";
    public static final String WTI = "WTI";
    /** In rupees a barrel. */
    public static final String MCX_CRUDE = "MCX_CRUDEOIL";
    /** In rupees per 10 grams, as MCX quotes it. */
    public static final String MCX_GOLD = "MCX_GOLD";
    /** In rupees a kilogram, as MCX quotes it. */
    public static final String MCX_SILVER = "MCX_SILVER";
    public static final List<String> ALL = List.of(US_10Y, US_2Y, BRENT, WTI, MCX_CRUDE, MCX_GOLD, MCX_SILVER);

    private final TreasuryYieldClient treasury;
    private final OilPricesClient oil;
    private final McxFuturesClient mcx;

    public MarketSources(TreasuryYieldClient treasury, OilPricesClient oil, McxFuturesClient mcx) {
        this.treasury = treasury;
        this.oil = oil;
        this.mcx = mcx;
    }

    @Override
    public List<Observation> apply(String series, LocalDate from) {
        return switch (series) {
            case US_10Y -> treasury.fetch("10 Yr", from);
            case US_2Y -> treasury.fetch("2 Yr", from);
            case BRENT -> oil.fetch("brent", from);
            case WTI -> oil.fetch("wti", from);
            case MCX_CRUDE -> mcx.fetch("CRUDEOIL", from);
            case MCX_GOLD -> mcx.fetch("GOLD", from);
            case MCX_SILVER -> mcx.fetch("SILVER", from);
            default -> throw new MarketDataException("no source for a series called " + series, null);
        };
    }

    /**
     * How far back before its newest reading a series is read again. The Treasury and the EIA can revise a recent
     * reading, and an oil price arrives a week late. An MCX close is final once the day is over, and reading further
     * back after a contract has expired would price those days off the next contract instead.
     */
    public int rereadDays(String series) {
        return series.startsWith("MCX_") ? 0 : MarketSeriesService.REREAD_DAYS;
    }
}
