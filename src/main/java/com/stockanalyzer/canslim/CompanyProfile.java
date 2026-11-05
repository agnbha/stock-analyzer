package com.stockanalyzer.canslim;

import java.time.YearMonth;
import java.util.List;

/**
 * What a screener.in company page says about the business, for showing next to its chart: the headline
 * ratios, growth, the last quarters' revenue and profit, who holds the shares, and what the company does.
 * Every figure is nullable: a page carries only what the company reports (an ETF has almost none, a bank
 * has no "Sales").
 *
 * <p>The price-dependent ratios (market cap, P/E, dividend yield) are as they stood when the page was read,
 * alongside that price, so they can be restated at today's price from the figures that do not move with
 * it: earnings per share, book value and the share count.
 *
 * @param marketCapCr    market capitalisation, in crore rupees, at {@code price}
 * @param price          the share price the page showed
 * @param high52w        the page's 52-week high and low, rounded as the page rounds them
 * @param dividendYield  percent, at {@code price}
 * @param epsTtm         earnings per share over the trailing twelve months, falling back to the last
 *                       financial year when the page shows no trailing column
 * @param debtToEquity   borrowings over equity capital plus reserves, from the latest balance sheet
 * @param salesGrowthTtm compounded growth figures as the page prints them, in percent
 * @param quarters       the latest reported quarters, oldest first
 * @param holdings       the latest quarters of the shareholding pattern, oldest first
 */
public record CompanyProfile(String about,
                             String classification,
                             Double marketCapCr,
                             Double price,
                             Double high52w,
                             Double low52w,
                             Double pe,
                             Double bookValue,
                             Double dividendYield,
                             Double roce,
                             Double roe,
                             Double faceValue,
                             Double epsTtm,
                             Double debtToEquity,
                             Double salesGrowthTtm,
                             Double salesGrowth3y,
                             Double profitGrowthTtm,
                             Double profitGrowth3y,
                             YearMonth resultsQuarter,
                             YearMonth holdingQuarter,
                             List<Quarter> quarters,
                             List<Holding> holdings) {

    /** One reported quarter, in crore rupees except per-share earnings. */
    public record Quarter(YearMonth period, Double sales, Double netProfit, Double eps) {
    }

    /** The shareholding pattern at the end of a quarter, in percent of shares, and the number of holders. */
    public record Holding(YearMonth period, Double promoters, Double fiis, Double diis, Double government,
                          Double publicShare, Double others, Double shareholders) {
    }

    public CompanyProfile {
        quarters = List.copyOf(quarters);
        holdings = List.copyOf(holdings);
    }

    /** A page with no figures on it: an ETF, or a symbol screener.in does not carry. */
    public boolean isEmpty() {
        return marketCapCr == null && quarters.isEmpty() && holdings.isEmpty();
    }
}
