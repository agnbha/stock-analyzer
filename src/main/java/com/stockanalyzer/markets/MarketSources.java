package com.stockanalyzer.markets;

import com.stockanalyzer.markets.NsdlFpiClient.Measure;
import com.stockanalyzer.markets.NseFiiDiiClient.Category;
import com.stockanalyzer.markets.NseFiiDiiClient.Figure;
import com.stockanalyzer.markets.NseParticipantOiClient.Participant;
import com.stockanalyzer.markets.NseParticipantOiClient.Side;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * The series two pages draw, both linked from CAN SLIM's header.
 *
 * <p>Bonds and Commodities: the US Treasury publishes its yields itself; the EIA's Brent and WTI prices come through the
 * open "oil-prices" dataset; MCX's crude oil, gold and silver come from Groww's candles of their futures, read with the
 * app's own Groww login.
 *
 * <p>Institutional Investment: NSDL's daily record of foreign portfolio investment (FPI) by reporting day; NSE's
 * provisional cash-market buying and selling by foreign (FII) and domestic (DII) institutions; and NSE's end-of-day
 * index-futures positions of FIIs, DIIs, proprietary traders and clients. Amounts are in rupees crore, positions in
 * contracts.
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
    public static final List<String> BONDS_AND_COMMODITIES =
            List.of(US_10Y, US_2Y, BRENT, WTI, MCX_CRUDE, MCX_GOLD, MCX_SILVER);

    /** NSDL's FPI net investment, one series a {@link Measure}: FPI_EQUITY, FPI_EQUITY_EXCHANGE, ... */
    private static final String FPI = "FPI_";
    /** NSE's cash-market figures: FII_CASH_BUY, FII_CASH_SELL, FII_CASH_NET, and the same for DII. */
    private static final String CASH = "_CASH_";
    /** NSE's index-futures open interest in contracts: IDX_FUT_FII_LONG, IDX_FUT_FII_SHORT, and the same for DII, PRO and
     * CLIENT. */
    private static final String OI = "IDX_FUT_";
    public static final List<String> INSTITUTIONAL = institutional();
    public static final List<String> ALL = concat(BONDS_AND_COMMODITIES, INSTITUTIONAL);

    /**
     * The series one run reads: everything, or only the institutional flows, which need no broker login. The flows come
     * out in the evening, after the close; a run that wants only those (see {@code ops/bin/flows.sh}) does not touch the
     * yields and prices, nor Groww for the MCX ones.
     */
    public static List<String> selection(boolean institutionalOnly) {
        return institutionalOnly ? INSTITUTIONAL : ALL;
    }

    private final TreasuryYieldClient treasury;
    private final OilPricesClient oil;
    private final McxFuturesClient mcx;
    private final NsdlFpiClient fpi;
    private final NseFiiDiiClient fiiDii;
    private final NseParticipantOiClient openInterest;

    public MarketSources(TreasuryYieldClient treasury, OilPricesClient oil, McxFuturesClient mcx, NsdlFpiClient fpi,
                         NseFiiDiiClient fiiDii, NseParticipantOiClient openInterest) {
        this.treasury = treasury;
        this.oil = oil;
        this.mcx = mcx;
        this.fpi = fpi;
        this.fiiDii = fiiDii;
        this.openInterest = openInterest;
    }

    @Override
    public List<Observation> apply(String series, LocalDate from) {
        switch (series) {
            case US_10Y:
                return treasury.fetch("10 Yr", from);
            case US_2Y:
                return treasury.fetch("2 Yr", from);
            case BRENT:
                return oil.fetch("brent", from);
            case WTI:
                return oil.fetch("wti", from);
            case MCX_CRUDE:
                return mcx.fetch("CRUDEOIL", from);
            case MCX_GOLD:
                return mcx.fetch("GOLD", from);
            case MCX_SILVER:
                return mcx.fetch("SILVER", from);
            default:
                break;
        }
        if (INSTITUTIONAL.contains(series)) {
            if (series.startsWith(FPI)) {
                return fpi.fetch(Measure.valueOf(series.substring(FPI.length())), from);
            }
            if (series.startsWith(OI)) {
                String[] parts = series.substring(OI.length()).split("_");
                return openInterest.fetch(Participant.valueOf(parts[0]), Side.valueOf(parts[1]), from);
            }
            String[] parts = series.split(CASH);
            return fiiDii.fetch(Category.valueOf(parts[0]), Figure.valueOf(parts[1]), from);
        }
        throw new MarketDataException("no source for a series called " + series, null);
    }

    /**
     * How far back before its newest reading a series is read again. The Treasury and the EIA can revise a recent
     * reading, and an oil price arrives a week late. An MCX close is final once the day is over, and reading further
     * back after a contract has expired would price those days off the next contract instead. NSDL's figures are read a
     * month at a time, so a week back costs nothing more; NSE's cash figures are only ever the latest day, and its open
     * interest files are final, so neither is read again.
     */
    public int rereadDays(String series) {
        if (series.startsWith("MCX_") || series.startsWith(OI) || series.contains(CASH)) {
            return 0;
        }
        return series.startsWith(FPI) ? 7 : MarketSeriesService.REREAD_DAYS;
    }

    private static List<String> institutional() {
        List<String> series = new ArrayList<>();
        for (Measure measure : Measure.values()) {
            series.add(FPI + measure);
        }
        for (Category category : Category.values()) {
            for (Figure figure : Figure.values()) {
                series.add(category + CASH + figure);
            }
        }
        for (Participant participant : Participant.values()) {
            for (Side side : Side.values()) {
                series.add(OI + participant + "_" + side);
            }
        }
        return List.copyOf(series);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }
}
