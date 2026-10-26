package com.stockanalyzer.canslim;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one page of a screener.in results table.
 *
 * <p>The table is regular enough that a full HTML parser would be a dependency
 * for nothing: one header row whose numeric columns carry the ratio's full name
 * in {@code data-tooltip}, then one {@code <tr data-row-company-id>} per company
 * whose second cell links to {@code /company/<SYMBOL>/}. Columns are matched by
 * that tooltip, never by position, because which columns appear depends on the
 * query and on the account's column settings.
 */
public final class ScreenerResultsParser {

    private static final Pattern TABLE = Pattern.compile(
            "<table[^>]*class=\"[^\"]*data-table[^\"]*\"[^>]*>(.*?)</table>", Pattern.DOTALL);
    // "<th" then whitespace or ">", so a <thead> is not mistaken for a column.
    private static final Pattern HEADER_CELL = Pattern.compile("<th(\\s[^>]*)?>", Pattern.DOTALL);
    private static final Pattern TOOLTIP = Pattern.compile("data-tooltip=\"([^\"]*)\"");
    private static final Pattern ROW = Pattern.compile(
            "<tr[^>]*data-row-company-id=\"[^\"]*\"[^>]*>(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL);
    private static final Pattern COMPANY_LINK = Pattern.compile(
            "<a[^>]*href=\"/company/([^/\"]+)/[^\"]*\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern PAGE_OF = Pattern.compile("page\\s+(\\d+)\\s+of\\s+(\\d+)");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern BSE_CODE = Pattern.compile("\\d+");

    private ScreenerResultsParser() {
    }

    /** One page of results, and how many pages the whole result spans. */
    public record Page(List<ScreenedStock> stocks, int totalPages, int skippedNonNse) {
    }

    /** Null when the HTML holds no results table at all - an error page, or a login form. */
    public static Page parse(String html) {
        Matcher table = TABLE.matcher(html);
        if (!table.find()) {
            return null;
        }
        String body = table.group(1);
        List<String> columns = headerColumns(body);

        List<ScreenedStock> stocks = new ArrayList<>();
        int skipped = 0;
        Matcher row = ROW.matcher(body);
        while (row.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cell = CELL.matcher(row.group(1));
            while (cell.find()) {
                cells.add(cell.group(1));
            }
            ScreenedStock stock = toStock(columns, cells);
            if (stock == null) {
                skipped++;
            } else {
                stocks.add(stock);
            }
        }

        Matcher pages = PAGE_OF.matcher(html);
        int totalPages = pages.find() ? Integer.parseInt(pages.group(2)) : 1;
        return new Page(stocks, totalPages, skipped);
    }

    /** The tooltip of each header cell in order; null for the S.No. and name columns. */
    private static List<String> headerColumns(String tableBody) {
        int firstRow = tableBody.indexOf("data-row-company-id");
        String header = firstRow < 0 ? tableBody : tableBody.substring(0, firstRow);
        List<String> columns = new ArrayList<>();
        Matcher th = HEADER_CELL.matcher(header);
        while (th.find()) {
            Matcher tooltip = TOOLTIP.matcher(th.group(1) == null ? "" : th.group(1));
            columns.add(tooltip.find() ? unescape(tooltip.group(1)).trim() : null);
        }
        return columns;
    }

    /**
     * Null for a company with no NSE symbol: screener links BSE-only listings by
     * their numeric scrip code, which the NSE candle feed cannot price.
     */
    private static ScreenedStock toStock(List<String> columns, List<String> cells) {
        String symbol = null;
        String name = null;
        Map<String, Double> ratios = new LinkedHashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            String cell = cells.get(i);
            Matcher link = COMPANY_LINK.matcher(cell);
            if (symbol == null && link.find()) {
                symbol = URLDecoder.decode(link.group(1), StandardCharsets.UTF_8).trim().toUpperCase();
                name = text(link.group(2));
                continue;
            }
            String column = i < columns.size() ? columns.get(i) : null;
            if (column != null) {
                Double value = number(text(cell));
                if (value != null) {
                    ratios.put(column, value);
                }
            }
        }
        if (symbol == null || symbol.isEmpty() || BSE_CODE.matcher(symbol).matches()) {
            return null;
        }
        return new ScreenedStock(symbol, name, ratios);
    }

    private static String text(String html) {
        return unescape(TAG.matcher(html).replaceAll("")).trim();
    }

    private static Double number(String text) {
        String cleaned = text.replace(",", "").replace("%", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String unescape(String text) {
        return text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
    }
}
