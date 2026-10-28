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

    private ScreenerCompanyParser() {
    }

    /** The ratios together with the latest quarter each quarterly table shows. */
    public static CompanyFundamentals fundamentals(String html) {
        return new CompanyFundamentals(parse(html),
                lastQuarter(section(html, "quarters")), lastQuarter(section(html, "quarterly-shp")));
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
        put(ratios, "Market Capitalization", top.get("Market Cap"));
        put(ratios, ScreenerRatios.ROE, top.get("ROE"));

        String quarters = section(html, "quarters");
        put(ratios, ScreenerRatios.QTR_PROFIT_GROWTH, yearOnYear(quarters, "Net Profit"));
        Double sales = yearOnYear(quarters, "Sales");
        put(ratios, ScreenerRatios.QTR_SALES_GROWTH, sales != null ? sales : yearOnYear(quarters, "Revenue"));

        put(ratios, ScreenerRatios.PROFIT_GROWTH_3Y, compoundedProfitGrowth3y(html));

        String holding = section(html, "quarterly-shp");
        put(ratios, ScreenerRatios.FII_CHANGE, lastChange(holding, "FIIs"));
        put(ratios, ScreenerRatios.DII_CHANGE, lastChange(holding, "DIIs"));
        return ratios;
    }

    private static void put(Map<String, Double> map, String key, Double value) {
        if (value != null) {
            map.put(key, value);
        }
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
