package com.stockanalyzer.canslim;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Scores the seven letters and decides the signal for one stock.
 *
 * <p><b>Letters.</b> C, A and I come from the screener's ratios; N, S and L from
 * the stock's own bars; M from the market assessment. A letter whose data is
 * missing is unknown rather than failed.
 *
 * <p><b>Sell signals</b> are O'Neil's exits, and apply to anything that has a
 * rally to end - a recent breakout, a held position, or a large enough advance
 * off a recent low - whether or not it passed today's screen:
 * <ol>
 *   <li>stop loss: the close is {@code stopLossPct} below the buy point;</li>
 *   <li>trend break: a close below the 50-day average on heavy volume;</li>
 *   <li>climax: well above the buy point, either up {@code climaxRunPct} in three
 *       weeks or posting its biggest gain since the breakout on its heaviest
 *       volume.</li>
 * </ol>
 *
 * <p><b>Buy signals</b> need everything CAN SLIM asks for at once: the stock
 * passed the screen, its known C, A and L are not failing, and it is breaking
 * out of - or sitting just under - a sound base. In a market correction the
 * same setup is reported as {@link CanSlimSignal#AVOID_MARKET} instead.
 */
public final class CanSlimEvaluator {

    private final CanSlimCriteria criteria;

    public CanSlimEvaluator(CanSlimCriteria criteria) {
        this.criteria = criteria;
    }

    public CanSlimScore evaluate(String symbol, CanSlimScore.Source source, ScreenedStock screened,
                                 PriceAction priceAction, Integer rsRating, MarketAssessment market,
                                 Double holdingCost) {
        Map<String, Double> ratios = screened == null ? Map.of() : screened.ratios();
        Double qtrProfit = ratios.get(ScreenerRatios.QTR_PROFIT_GROWTH);
        Double qtrSales = ratios.get(ScreenerRatios.QTR_SALES_GROWTH);
        Double profit3y = ratios.get(ScreenerRatios.PROFIT_GROWTH_3Y);
        Double roe = ratios.get(ScreenerRatios.ROE);
        Double institutional = sum(ratios.get(ScreenerRatios.FII_CHANGE), ratios.get(ScreenerRatios.DII_CHANGE));

        CanSlimScore.Letters letters = new CanSlimScore.Letters(
                qtrProfit == null ? null
                        : qtrProfit >= criteria.minQtrProfitGrowthPct()
                        && (qtrSales == null || qtrSales >= criteria.minQtrSalesGrowthPct()),
                profit3y == null ? null
                        : profit3y >= criteria.minProfitGrowth3yPct() && (roe == null || roe >= criteria.minRoePct()),
                priceAction == null || priceAction.pctFromHigh() == null ? null
                        : priceAction.pctFromHigh() <= criteria.maxPctFromHigh(),
                priceAction == null || priceAction.upDownVolumeRatio() == null ? null
                        : priceAction.upDownVolumeRatio() >= criteria.minUpDownVolumeRatio(),
                rsRating == null ? null : rsRating >= criteria.minRsRating(),
                institutional == null ? null : institutional > 0,
                switch (market.state()) {
                    case CONFIRMED_UPTREND -> true;
                    case UNKNOWN -> null;
                    default -> false;
                });

        Double buyPoint = holdingCost != null ? holdingCost
                : priceAction != null && priceAction.breakout() != null ? priceAction.breakout().pivot() : null;
        Decision decision = priceAction == null
                ? new Decision(CanSlimSignal.NONE, "no daily prices could be fetched")
                : decide(source, letters, priceAction, buyPoint, holdingCost != null, market);

        return new CanSlimScore(symbol, source, letters, qtrProfit, qtrSales, profit3y, roe, institutional,
                rsRating, priceAction, buyPoint, decision.signal(), decision.reason(), ratios);
    }

    private record Decision(CanSlimSignal signal, String reason) {
    }

    private Decision decide(CanSlimScore.Source source, CanSlimScore.Letters letters, PriceAction pa,
                            Double buyPoint, boolean held, MarketAssessment market) {
        Decision sell = sellDecision(pa, buyPoint, held);
        if (sell != null) {
            return sell;
        }
        // A screener result already passed the earnings query. Anything else is a buy candidate only
        // if its own fundamentals show both C and A passing - not merely nothing failing.
        if (source != CanSlimScore.Source.SCREENER
                && !(Boolean.TRUE.equals(letters.c()) && Boolean.TRUE.equals(letters.a()))) {
            return new Decision(CanSlimSignal.NONE, letters.c() == null && letters.a() == null
                    ? "no fundamentals available; checked for sell signals only"
                    : "earnings do not qualify (C " + passFail(letters.c()) + ", A " + passFail(letters.a())
                    + "); checked for sell signals only");
        }

        List<String> failing = new ArrayList<>();
        if (Boolean.FALSE.equals(letters.c())) {
            failing.add("C (current earnings)");
        }
        if (Boolean.FALSE.equals(letters.a())) {
            failing.add("A (annual earnings)");
        }
        if (Boolean.FALSE.equals(letters.l())) {
            failing.add("L (RS below " + criteria.minRsRating() + ")");
        }
        if (!failing.isEmpty()) {
            return new Decision(CanSlimSignal.NONE, "fails " + String.join(", ", failing));
        }

        Decision buy = buyDecision(pa);
        if (buy == null) {
            return new Decision(CanSlimSignal.NONE, pa.base() == null
                    ? "not in a base"
                    : String.format(Locale.ROOT, "in a base but %.1f%% from its pivot %.2f",
                    pa.base().pctFromPivot(), pa.base().pivot()));
        }
        if (buy.signal() == CanSlimSignal.EXTENDED) {
            return buy;
        }
        return switch (market.state()) {
            case CORRECTION -> new Decision(CanSlimSignal.AVOID_MARKET,
                    "market in correction - " + buy.reason() + " (would be " + buy.signal() + ")");
            case UPTREND_UNDER_PRESSURE -> new Decision(buy.signal(),
                    buy.reason() + "; market uptrend under pressure, size positions down");
            default -> buy;
        };
    }

    private static String passFail(Boolean letter) {
        return letter == null ? "unknown" : letter ? "passes" : "fails";
    }

    private Decision sellDecision(PriceAction pa, Double buyPoint, boolean held) {
        boolean hadRally = held || pa.breakout() != null
                || (pa.advanceFromLowPct() != null && pa.advanceFromLowPct() >= criteria.rallyMinPct());
        String basis = held ? "holding cost" : "breakout pivot";

        if (buyPoint != null && pa.close() <= buyPoint * (1 - criteria.stopLossPct() / 100.0)) {
            return new Decision(CanSlimSignal.SELL_STOP_LOSS, String.format(Locale.ROOT,
                    "close %.2f is %.1f%% below the %s %.2f (limit %.0f%%)",
                    pa.close(), -Bars.pctChange(buyPoint, pa.close()), basis, buyPoint, criteria.stopLossPct()));
        }
        if (!hadRally) {
            return null;
        }
        PriceAction.FiftyDayBreak fiftyDay = pa.fiftyDayBreak();
        if (fiftyDay != null && fiftyDay.volumeRatio() != null
                && fiftyDay.volumeRatio() >= criteria.heavyVolumeRatio()) {
            return new Decision(CanSlimSignal.SELL_TREND_BREAK, String.format(Locale.ROOT,
                    "closed below its 50-day average %.2f on %s at %.1fx average volume",
                    pa.sma50(), fiftyDay.date(), fiftyDay.volumeRatio()));
        }

        Double reference = buyPoint != null ? buyPoint : pa.sma50();
        if (reference != null) {
            double above = Bars.pctChange(reference, pa.close());
            boolean climaxRun = pa.gainOverClimaxWindowPct() != null
                    && pa.gainOverClimaxWindowPct() >= criteria.climaxRunPct();
            if (above >= criteria.climaxMinGainPct() && (climaxRun || pa.exhaustionDay())) {
                return new Decision(CanSlimSignal.SELL_CLIMAX, String.format(Locale.ROOT,
                        "%.1f%% above its %s %.2f and %s",
                        above, buyPoint != null ? basis : "50-day average", reference,
                        climaxRun ? String.format(Locale.ROOT, "up %.1f%% in %d sessions",
                                pa.gainOverClimaxWindowPct(), CanSlimCriteria.CLIMAX_WINDOW)
                                : "posted its biggest gain since the breakout on its heaviest volume"));
            }
        }
        return null;
    }

    private Decision buyDecision(PriceAction pa) {
        PriceAction.Breakout breakout = pa.breakout();
        if (breakout != null && breakout.sessionsAgo() <= criteria.buyWindowSessions()
                && breakout.pctFromPivot() >= 0 && breakout.pctFromPivot() <= criteria.buyRangePct()) {
            return new Decision(breakout.sessionsAgo() == 0 ? CanSlimSignal.BUY_BREAKOUT : CanSlimSignal.BUY_ZONE,
                    String.format(Locale.ROOT, "broke out over the pivot %.2f on %s at %.1fx average volume; "
                                    + "now %.1f%% above it (buy range to %.2f)",
                            breakout.pivot(), breakout.date(), breakout.volumeRatio(), breakout.pctFromPivot(),
                            breakout.pivot() * (1 + criteria.buyRangePct() / 100.0)));
        }
        if (breakout != null && breakout.pctFromPivot() > criteria.buyRangePct()) {
            return new Decision(CanSlimSignal.EXTENDED, String.format(Locale.ROOT,
                    "%.1f%% above the pivot %.2f it broke out of on %s - past the %.0f%% buy range",
                    breakout.pctFromPivot(), breakout.pivot(), breakout.date(), criteria.buyRangePct()));
        }
        PriceAction.Base base = pa.base();
        if (base != null && base.pctFromPivot() >= -criteria.watchWithinPct()
                && base.pctFromPivot() <= criteria.buyRangePct()) {
            String volume = pa.dryUpRatio() == null ? ""
                    : String.format(Locale.ROOT, "; volume %s (%.2fx its 50-day average)",
                    pa.dryUpRatio() < 1 ? "drying up" : "not drying up", pa.dryUpRatio());
            String position = base.pctFromPivot() > 0
                    ? String.format(Locale.ROOT, "%.1f%% over the pivot %.2f without breakout volume",
                    base.pctFromPivot(), base.pivot())
                    : String.format(Locale.ROOT, "%.1f%% under the pivot %.2f", -base.pctFromPivot(), base.pivot());
            return new Decision(CanSlimSignal.WATCH_SETUP, String.format(Locale.ROOT,
                    "%s, in a %d-session base %.1f%% deep%s; a close above %.2f on %.1fx volume is the breakout",
                    position, base.sessions(), base.depthPct(), volume, base.pivot(),
                    criteria.breakoutVolumeRatio()));
        }
        return null;
    }

    private static Double sum(Double a, Double b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a + b;
    }
}
