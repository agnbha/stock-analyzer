package com.stockanalyzer.canslim;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the fundamentals CAN SLIM needs off a public screener.in company page
 * ({@code /company/<SYMBOL>/consolidated/}), under the same ratio names the
 * query results use - so a stock scored from its page and one scored from a
 * query result are indistinguishable downstream.
 *
 * <ul>
 *   <li><b>C</b> - the latest quarter's net profit and sales against the same
 *       quarter a year earlier (four columns back in the quarterly table). A
 *       base of zero or less has no meaningful growth rate and is left unknown.</li>
 *   <li><b>A</b> - the "Compounded Profit Growth, 3 Years" figure, and the
 *       top-ratios ROE.</li>
 *   <li><b>I</b> - the change in FII and DII holding between the last two
 *       quarters of the shareholding table, in percentage points.</li>
 * </ul>
 *
 * A figure the page does not carry (an ETF, a new listing) is simply absent.
 * Banks label their top line "Revenue" rather than "Sales"; both are read.
 */
public final class ScreenerCompanyParser {

    private static final Pattern SECTION_END = Pattern.compile("</section>", Pattern.DOTALL);
    /** The element itself, not a tab button naming it in {@code data-tab-id="quarterly-shp"}. */
    private static final Pattern QUARTERLY_SHAREHOLDING = Pattern.compile("\\sid=\"quarterly-shp\"");
    private static final Pattern TOP_RATIO = Pattern.compile(
            "<span class=\"name\">\\s*(.*?)\\s*</span>\\s*<span class=\"nowrap value\">(.*?)</span>\\s*</li>",
            Pattern.DOTALL);
    private static final Pattern NUMBER_SPAN = Pattern.compile("class=\"number\">([^<]*)<");
    private static final Pattern ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<t[dh][^>]*>(.*?)</t[dh]>", Pattern.DOTALL);
    private static final Pattern RANGES = Pattern.compile(
            "<table class=\"ranges-table\">(.*?)</table>", Pattern.DOTALL);
    private static final Pattern THREE_YEARS = Pattern.compile("3 Years:\\s*(-?[\\d,.]+)\\s*%");
    private static final Pattern NUMBER = Pattern.compile("-?[\\d,]+(?:\\.\\d+)?");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final DateTimeFormatter QUARTER = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
    private static final Pattern ABOUT = Pattern.compile(
            "<div class=\"sub show-more-box about\"[^>]*>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern CITATION = Pattern.compile("<sup>.*?</sup>", Pattern.DOTALL);
    private static final Pattern MARKET_LINK = Pattern.compile(
            "<a[^>]*href=\"/market/[^\"]*\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    /** How many of the latest quarters a profile keeps, of revenue, profit and shareholding alike. */
    private static final int PROFILE_QUARTERS = 8;
    private static final int ABOUT_LIMIT = 1200;

    private ScreenerCompanyParser() {
    }

    /** The ratios together with the latest quarter each quarterly table shows. */
    public static CompanyFundamentals fundamentals(String html) {
        return new CompanyFundamentals(parse(html),
                lastQuarter(section(html, "quarters")), lastQuarter(quarterlyShareholding(html)));
    }

    /**
     * What a profile shows, read from the same page: the headline ratios, growth, the latest quarters'
     * revenue and profit, the shareholding pattern and what the company does. A figure the page lacks is
     * null, so a bank (no "Sales", no trailing column) or an ETF still yields what it has.
     */
    public static CompanyProfile profile(String html) {
        Map<String, List<Double>> top = topRatioNumbers(section(html, "top-ratios"));
        List<Double> highLow = top.getOrDefault("High / Low", List.of());
        String quarters = section(html, "quarters");
        String holding = quarterlyShareholding(html);
        return new CompanyProfile(
                about(html), classification(html),
                first(top, "Market Cap"), first(top, "Current Price"),
                highLow.size() > 0 ? highLow.get(0) : null, highLow.size() > 1 ? highLow.get(1) : null,
                first(top, "Stock P/E"), first(top, "Book Value"), first(top, "Dividend Yield"),
                first(top, "ROCE"), first(top, "ROE"), first(top, "Face Value"),
                epsTtm(section(html, "profit-loss")), debtToEquity(section(html, "balance-sheet")),
                compounded(html, "Compounded Sales Growth", "TTM:"),
                compounded(html, "Compounded Sales Growth", "3 Years:"),
                compounded(html, "Compounded Profit Growth", "TTM:"),
                compounded(html, "Compounded Profit Growth", "3 Years:"),
                lastQuarter(quarters), lastQuarter(holding),
                quarterRows(quarters), holdingRows(holding));
    }

    private static Double first(Map<String, List<Double>> values, String name) {
        List<Double> list = values.get(name);
        return list == null || list.isEmpty() ? null : list.getFirst();
    }

    /** Every number in each top-ratio's value: "High / Low" carries two. */
    private static Map<String, List<Double>> topRatioNumbers(String section) {
        Map<String, List<Double>> values = new LinkedHashMap<>();
        Matcher item = TOP_RATIO.matcher(section);
        while (item.find()) {
            List<Double> numbers = new ArrayList<>();
            Matcher number = NUMBER_SPAN.matcher(item.group(2));
            while (number.find()) {
                Double value = number(number.group(1));
                if (value != null) {
                    numbers.add(value);
                }
            }
            values.put(text(item.group(1)), numbers);
        }
        return values;
    }

    /** The "About" paragraphs without their footnote links, cut at a sentence once they run long. */
    private static String about(String html) {
        Matcher box = ABOUT.matcher(html);
        if (!box.find()) {
            return null;
        }
        String about = decode(text(CITATION.matcher(box.group(1)).replaceAll("")));
        if (about.isEmpty()) {
            return null;
        }
        if (about.length() <= ABOUT_LIMIT) {
            return about;
        }
        String cut = about.substring(0, ABOUT_LIMIT);
        int sentence = cut.lastIndexOf(". ");
        return sentence > ABOUT_LIMIT / 3 ? cut.substring(0, sentence + 1) : cut.stripTrailing() + "\u2026";
    }

    /** The sector, industry and so on the page files the company under, broadest first. */
    private static String classification(String html) {
        List<String> names = new ArrayList<>();
        Matcher link = MARKET_LINK.matcher(html);
        while (link.find()) {
            String name = decode(text(link.group(1)));
            if (!name.isEmpty() && !names.contains(name)) {
                names.add(name);
            }
        }
        return names.isEmpty() ? null : String.join(" \u203a ", names);
    }

    /** Trailing-twelve-month EPS, or the last financial year's when the page shows no such column. */
    private static Double epsTtm(String profitAndLoss) {
        List<Double> eps = rowValues(profitAndLoss, "EPS in Rs");
        List<String> header = headerCells(profitAndLoss);
        int trailing = header.indexOf("TTM") - 1;  // the label cell leads the header but not the values
        if (trailing >= 0 && trailing < eps.size() && eps.get(trailing) != null) {
            return eps.get(trailing);
        }
        return lastValue(eps);
    }

    /** Borrowings over shareholders' funds (equity capital plus reserves) in the latest balance sheet. */
    private static Double debtToEquity(String balanceSheet) {
        Double equity = lastValue(rowValues(balanceSheet, "Equity Capital"));
        Double reserves = lastValue(rowValues(balanceSheet, "Reserves"));
        Double borrowings = lastValue(rowValues(balanceSheet, "Borrowings"));
        if (equity == null || reserves == null || borrowings == null || equity + reserves <= 0) {
            return null;
        }
        return borrowings / (equity + reserves);
    }

    /** One figure from a "Compounded ... Growth" box, e.g. {@code compounded(html, "Compounded Sales Growth", "TTM:")}. */
    private static Double compounded(String html, String title, String period) {
        Pattern wanted = Pattern.compile(Pattern.quote(period) + "\\s*(-?[\\d,.]+)\\s*%");
        Matcher table = RANGES.matcher(html);
        while (table.find()) {
            String body = text(table.group(1));
            if (body.startsWith(title)) {
                Matcher value = wanted.matcher(body);
                return value.find() ? number(value.group(1)) : null;
            }
        }
        return null;
    }

    private static List<CompanyProfile.Quarter> quarterRows(String quarters) {
        List<YearMonth> periods = periods(quarters);
        List<Double> sales = rowValues(quarters, "Sales");
        if (sales.isEmpty()) {
            sales = rowValues(quarters, "Revenue");
        }
        List<Double> profit = rowValues(quarters, "Net Profit");
        List<Double> eps = rowValues(quarters, "EPS in Rs");
        List<CompanyProfile.Quarter> rows = new ArrayList<>();
        for (int i = Math.max(0, periods.size() - PROFILE_QUARTERS); i < periods.size(); i++) {
            if (periods.get(i) != null) {
                rows.add(new CompanyProfile.Quarter(periods.get(i), at(sales, i), at(profit, i), at(eps, i)));
            }
        }
        return rows;
    }

    private static List<CompanyProfile.Holding> holdingRows(String holding) {
        List<YearMonth> periods = periods(holding);
        List<Double> promoters = rowValues(holding, "Promoters");
        List<Double> fiis = rowValues(holding, "FIIs");
        List<Double> diis = rowValues(holding, "DIIs");
        List<Double> government = rowValues(holding, "Government");
        List<Double> publicShare = rowValues(holding, "Public");
        List<Double> others = rowValues(holding, "Others");
        List<Double> holders = rowValues(holding, "No. of Shareholders");
        List<CompanyProfile.Holding> rows = new ArrayList<>();
        for (int i = Math.max(0, periods.size() - PROFILE_QUARTERS); i < periods.size(); i++) {
            if (periods.get(i) != null) {
                rows.add(new CompanyProfile.Holding(periods.get(i), at(promoters, i), at(fiis, i), at(diis, i),
                        at(government, i), at(publicShare, i), at(others, i), at(holders, i)));
            }
        }
        return rows;
    }

    /** The header row's cells, the empty label cell first. */
    private static List<String> headerCells(String section) {
        Matcher row = ROW.matcher(section);
        List<String> cells = new ArrayList<>();
        if (row.find()) {
            Matcher cell = CELL.matcher(row.group(1));
            while (cell.find()) {
                cells.add(text(cell.group(1)));
            }
        }
        return cells;
    }

    /** The month each value column stands for, null for one that is not a month ("TTM"); aligned with {@link #rowValues}. */
    private static List<YearMonth> periods(String section) {
        List<String> header = headerCells(section);
        List<YearMonth> periods = new ArrayList<>();
        for (String heading : header.isEmpty() ? List.<String>of() : header.subList(1, header.size())) {
            try {
                periods.add(YearMonth.parse(heading, QUARTER));
            } catch (DateTimeParseException notAMonth) {
                periods.add(null);
            }
        }
        return periods;
    }

    private static Double at(List<Double> values, int index) {
        return index < values.size() ? values.get(index) : null;
    }

    private static Double lastValue(List<Double> values) {
        return values.isEmpty() ? null : values.getLast();
    }

    /** The character references that turn up in screener.in's prose. */
    private static String decode(String text) {
        return text.replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&apos;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&rsquo;", "\u2019").replace("&lsquo;", "\u2018")
                .replace("&ldquo;", "\u201c").replace("&rdquo;", "\u201d").replace("&ndash;", "\u2013")
                .replace("&mdash;", "\u2014").replace("&amp;", "&");
    }

    /** The newest column heading that reads as a month and year ("Sep 2026"), or null. */
    private static YearMonth lastQuarter(String section) {
        Matcher row = ROW.matcher(section);
        if (!row.find()) {
            return null;
        }
        YearMonth last = null;
        Matcher cell = CELL.matcher(row.group(1));
        while (cell.find()) {
            try {
                last = YearMonth.parse(text(cell.group(1)), QUARTER);
            } catch (DateTimeParseException notAMonth) {
                // The row's label cell, or a heading like "TTM".
            }
        }
        return last;
    }

    /** Empty when the page has none of it - the caller treats that as "no fundamentals". */
    public static Map<String, Double> parse(String html) {
        Map<String, Double> ratios = new LinkedHashMap<>();

        Map<String, Double> top = topRatios(section(html, "top-ratios"));
        put(ratios, ScreenerRatios.MARKET_CAP, top.get("Market Cap"));
        put(ratios, ScreenerRatios.ROE, top.get("ROE"));

        String quarters = section(html, "quarters");
        put(ratios, ScreenerRatios.QTR_PROFIT_GROWTH, yearOnYear(quarters, "Net Profit"));
        Double sales = yearOnYear(quarters, "Sales");
        put(ratios, ScreenerRatios.QTR_SALES_GROWTH, sales != null ? sales : yearOnYear(quarters, "Revenue"));

        put(ratios, ScreenerRatios.PROFIT_GROWTH_3Y, compoundedProfitGrowth3y(html));

        String holding = quarterlyShareholding(html);
        put(ratios, ScreenerRatios.FII_CHANGE, lastChange(holding, "FIIs"));
        put(ratios, ScreenerRatios.DII_CHANGE, lastChange(holding, "DIIs"));
        return ratios;
    }

    private static void put(Map<String, Double> map, String key, Double value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /**
     * The quarterly shareholding table and nothing after it. The shareholding section holds a quarterly and a yearly
     * table one after the other, and its tab buttons carry both names in {@code data-tab-id}. Read to the end of the
     * section, a row the quarterly table lacks was found in the yearly one instead and its years read as quarters:
     * HDFC Bank, which has had no promoters since its 2023 merger, showed the 25.97% of March 2021 as December 2024's.
     */
    private static String quarterlyShareholding(String html) {
        Matcher element = QUARTERLY_SHAREHOLDING.matcher(html);
        if (!element.find()) {
            return "";
        }
        int start = element.start();
        int stop = html.indexOf("</table>", start);
        return html.substring(start, stop < 0 ? Math.min(html.length(), start + 80_000) : stop);
    }

    /** The markup from {@code id="<id>"} to the end of its section; empty when the page lacks it. */
    private static String section(String html, String id) {
        int start = html.indexOf("id=\"" + id + "\"");
        if (start < 0) {
            return "";
        }
        Matcher end = SECTION_END.matcher(html);
        int stop = end.find(start) ? end.start() : Math.min(html.length(), start + 80_000);
        return html.substring(start, stop);
    }

    private static Map<String, Double> topRatios(String section) {
        Map<String, Double> values = new LinkedHashMap<>();
        Matcher item = TOP_RATIO.matcher(section);
        while (item.find()) {
            Matcher number = NUMBER_SPAN.matcher(item.group(2));
            if (number.find()) {
                put(values, text(item.group(1)), number(number.group(1)));
            }
        }
        return values;
    }

    /** Latest value against the one four columns (quarters) earlier, as a percentage. */
    private static Double yearOnYear(String quarters, String label) {
        List<Double> values = rowValues(quarters, label);
        int n = values.size();
        if (n < 5) {
            return null;
        }
        Double latest = values.get(n - 1);
        Double yearAgo = values.get(n - 5);
        if (latest == null || yearAgo == null || yearAgo <= 0) {
            return null;
        }
        return (latest - yearAgo) / yearAgo * 100.0;
    }

    private static Double compoundedProfitGrowth3y(String html) {
        Matcher table = RANGES.matcher(html);
        while (table.find()) {
            String body = text(table.group(1));
            if (body.startsWith("Compounded Profit Growth")) {
                Matcher years = THREE_YEARS.matcher(body);
                return years.find() ? number(years.group(1)) : null;
            }
        }
        return null;
    }

    /** What a holder category's share moved by between its last two reported quarters. */
    private static Double lastChange(String holding, String label) {
        List<Double> values = rowValues(holding, label);
        int n = values.size();
        if (n < 2 || values.get(n - 1) == null || values.get(n - 2) == null) {
            return null;
        }
        return values.get(n - 1) - values.get(n - 2);
    }

    /** Every value cell of the row whose first cell reads {@code label}; unparseable cells are null. */
    private static List<Double> rowValues(String section, String label) {
        Matcher row = ROW.matcher(section);
        while (row.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cell = CELL.matcher(row.group(1));
            while (cell.find()) {
                cells.add(text(cell.group(1)));
            }
            if (cells.size() > 1 && cells.getFirst().replace("+", "").trim().equals(label)) {
                List<Double> values = new ArrayList<>();
                for (String value : cells.subList(1, cells.size())) {
                    values.add(number(value));
                }
                return values;
            }
        }
        return List.of();
    }

    private static String text(String html) {
        return TAG.matcher(html).replaceAll(" ").replace("&nbsp;", " ").replace("&amp;", "&")
                .replaceAll("\\s+", " ").trim();
    }

    private static Double number(String text) {
        Matcher number = NUMBER.matcher(text.replace("₹", ""));
        if (!number.find()) {
            return null;
        }
        try {
            return Double.parseDouble(number.group().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
