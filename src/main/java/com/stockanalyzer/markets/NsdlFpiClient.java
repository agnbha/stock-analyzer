package com.stockanalyzer.markets;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads NSDL's daily trends in FPI investment, the depositories' record of what foreign portfolio investors bought and
 * sold, from the archive on NSDL's FPI site (www.fpi.nsdl.co.in, Reports, Archive), with no key.
 *
 * <p>The archive is an ASP.NET form: asked for a date, it answers with every reporting day of that month up to the
 * date, so a month costs one request and a run reads each month once, whichever figure is asked for. A reporting day's
 * figures are the trades the custodians reported for the trading day before it.
 *
 * <p>NSDL's site drops the connection for the default names that HTTP libraries send, so requests carry this app's own
 * name, as the other market clients do.
 */
public final class NsdlFpiClient {

    /** The figures kept from a reporting day, each in rupees crore, purchases less sales. */
    public enum Measure {
        /** Equity, every route. */
        EQUITY,
        /** Equity bought and sold on the stock exchanges. */
        EQUITY_EXCHANGE,
        /** Equity through the primary market and other routes: IPOs, placements, block deals off the exchange. */
        EQUITY_PRIMARY,
        /** Debt under every limit: the general limit, the voluntary retention route and the fully accessible route. */
        DEBT,
        /** Every category NSDL reports, equity, debt, hybrid, mutual funds and AIFs. */
        TOTAL
    }

    /** One reporting day. */
    record Day(double equity, double equityExchange, double equityPrimary, double debt, double total) {
        double get(Measure measure) {
            return switch (measure) {
                case EQUITY -> equity;
                case EQUITY_EXCHANGE -> equityExchange;
                case EQUITY_PRIMARY -> equityPrimary;
                case DEBT -> debt;
                case TOTAL -> total;
            };
        }
    }

    static final DateTimeFormatter NSDL_DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);
    private static final String HEADING = "Daily Trends in FPI Investments";
    private static final Pattern HIDDEN = Pattern.compile(
            "<input[^>]*type=\"hidden\"[^>]*name=\"(__[A-Z]+)\"[^>]*value=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROW = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>");
    private static final Pattern CELL = Pattern.compile("(?is)<td[^>]*>(.*?)</td>");
    private static final Pattern DATE = Pattern.compile("\\d{2}-[A-Z][a-z]{2}-\\d{4}");

    private final HttpClient httpClient;
    private final String url;
    private final Clock clock;
    private final ZoneId zone;
    private final Duration pause;
    private Map<String, String> form;
    private boolean asked;
    private final Map<YearMonth, SortedMap<LocalDate, Day>> months = new HashMap<>();
    private final Map<YearMonth, MarketDataException> failed = new HashMap<>();

    /**
     * @param url   the archive page, Archive.aspx
     * @param pause between two requests, so a first read of two years does not hurry the site
     */
    public NsdlFpiClient(HttpClient httpClient, String url, Clock clock, ZoneId zone, Duration pause) {
        this.httpClient = httpClient;
        this.url = url;
        this.clock = clock;
        this.zone = zone;
        this.pause = pause;
    }

    /** One figure for every reporting day from {@code from} on, oldest first. */
    public List<Observation> fetch(Measure measure, LocalDate from) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        List<Observation> observations = new ArrayList<>();
        for (YearMonth month = YearMonth.from(from); !month.isAfter(YearMonth.from(today)); month = month.plusMonths(1)) {
            for (Map.Entry<LocalDate, Day> day : month(month, today).entrySet()) {
                if (!day.getKey().isBefore(from)) {
                    observations.add(new Observation(day.getKey(), day.getValue().get(measure)));
                }
            }
        }
        return observations;
    }

    private SortedMap<LocalDate, Day> month(YearMonth month, LocalDate today) {
        if (failed.containsKey(month)) {
            throw failed.get(month);
        }
        SortedMap<LocalDate, Day> days = months.get(month);
        if (days != null) {
            return days;
        }
        try {
            LocalDate upTo = month.atEndOfMonth().isAfter(today) ? today : month.atEndOfMonth();
            days = parse(post(upTo));
            if (days.isEmpty() && month.isBefore(YearMonth.from(today))) {
                throw new MarketDataException("NSDL's archive has no FPI figures for " + month, null);
            }
            months.put(month, days);
            return days;
        } catch (MarketDataException e) {
            failed.put(month, e);
            throw e;
        }
    }

    private String post(LocalDate upTo) {
        if (form == null) {
            form = hiddenFields(send(request().GET().build()));
        }
        Map<String, String> fields = new LinkedHashMap<>(form);
        fields.put("__EVENTTARGET", "btnSubmit1");
        fields.put("__EVENTARGUMENT", "");
        fields.put("hdnDate", NSDL_DATE.format(upTo));
        fields.put("HdnValexceldata", "");
        fields.put("hdnFlag", "");
        String body = fields.entrySet().stream()
                .map(f -> URLEncoder.encode(f.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(f.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return send(request().header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private HttpRequest.Builder request() {
        return HttpRequest.newBuilder(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "stock-analyzer/1.0");
    }

    private String send(HttpRequest request) {
        try {
            if (asked && !pause.isZero()) {
                Thread.sleep(pause.toMillis());
            }
            asked = true;
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new MarketDataException("NSDL's FPI archive answered HTTP " + response.statusCode(), null);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MarketDataException("interrupted while reading NSDL's FPI archive", e);
        } catch (java.io.IOException e) {
            throw new MarketDataException("could not reach NSDL's FPI archive: " + e.getMessage(), e);
        }
    }

    /** The form's own hidden fields (its view state), which every request for a date must send back. */
    static Map<String, String> hiddenFields(String page) {
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher m = HIDDEN.matcher(page);
        while (m.find()) {
            fields.put(m.group(1), unescape(m.group(2)));
        }
        if (!fields.containsKey("__VIEWSTATE")) {
            throw new MarketDataException("NSDL's FPI archive page has no form to ask for a date", null);
        }
        return fields;
    }

    /**
     * The investment table: a reporting day spans several rows, a category (Equity, Debt-General Limit, Debt-VRR,
     * Debt-FAR, Hybrid, Mutual Funds, AIFs) spans its routes and a sub-total, and the day ends with a Total row. Each
     * figure row reads purchases, sales, net, net in US dollars; a net sale is written in brackets. A month's last day
     * is followed by the month's and the year's totals, which are left out. A page without the table has no reporting
     * day yet. The derivatives table further down the page is not read.
     */
    static SortedMap<LocalDate, Day> parse(String page) {
        SortedMap<LocalDate, Day> days = new TreeMap<>();
        int start = page.indexOf(HEADING);
        if (start < 0) {
            return days;
        }
        int end = page.indexOf("<table", start);
        String table = page.substring(start, end < 0 ? page.length() : end);
        LocalDate date = null;
        String category = null;
        Map<String, Double> net = new LinkedHashMap<>();
        Double total = null;
        double exchange = 0;
        double primary = 0;
        Matcher row = ROW.matcher(table);
        while (row.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cell = CELL.matcher(row.group(1));
            while (cell.find()) {
                cells.add(text(cell.group(1)));
            }
            if (cells.isEmpty()) {
                continue;
            }
            if (DATE.matcher(cells.getFirst()).matches()) {
                if (date != null) {
                    days.put(date, day(date, net, exchange, primary, total));
                }
                try {
                    date = LocalDate.parse(cells.getFirst(), NSDL_DATE);
                } catch (DateTimeParseException e) {
                    throw new MarketDataException("NSDL's FPI table has an unreadable date: " + cells.getFirst(), e);
                }
                cells.removeFirst();
                net = new LinkedHashMap<>();
                total = null;
                exchange = 0;
                primary = 0;
            }
            if (date == null || cells.isEmpty()) {
                continue;
            }
            if (cells.getFirst().startsWith("Total for")) {
                break;
            }
            if (cells.getFirst().equals("Total")) {
                total = amount(cells, 3);
                continue;
            }
            if (cells.size() >= 6 && !looksLikeAmount(cells.get(1))) {
                category = cells.removeFirst();
            }
            if (category == null || cells.size() < 5) {
                continue;
            }
            String route = cells.getFirst();
            double value = amount(cells, 3);
            if (route.equals("Sub-total")) {
                net.put(category, value);
            } else if (category.equals("Equity") && route.equals("Stock Exchange")) {
                exchange = value;
            } else if (category.equals("Equity") && route.startsWith("Primary market")) {
                primary = value;
            }
        }
        if (date != null) {
            days.put(date, day(date, net, exchange, primary, total));
        }
        return days;
    }

    private static Day day(LocalDate date, Map<String, Double> net, double exchange, double primary, Double total) {
        if (!net.containsKey("Equity") || total == null) {
            throw new MarketDataException("NSDL's FPI table for " + date + " has no equity sub-total or no total", null);
        }
        double debt = net.entrySet().stream().filter(e -> e.getKey().startsWith("Debt"))
                .mapToDouble(Map.Entry::getValue).sum();
        return new Day(net.get("Equity"), exchange, primary, Math.round(debt * 100) / 100.0, total);
    }

    /** The {@code index}th cell, an amount such as "21,334.02", or "(522.18)" for a net sale. */
    private static double amount(List<String> cells, int index) {
        if (cells.size() <= index) {
            throw new MarketDataException("NSDL's FPI table has a short row: " + cells, null);
        }
        String text = cells.get(index).replace(",", "").strip();
        boolean negative = text.startsWith("(") && text.endsWith(")");
        try {
            double value = Double.parseDouble(negative ? text.substring(1, text.length() - 1) : text);
            return negative ? -value : value;
        } catch (NumberFormatException e) {
            throw new MarketDataException("NSDL's FPI table has an unreadable amount: " + cells.get(index), e);
        }
    }

    private static boolean looksLikeAmount(String text) {
        return text.matches("\\(?[\\d,]+(\\.\\d+)?\\)?");
    }

    private static String text(String html) {
        return unescape(html.replaceAll("<[^>]+>", " ")).replaceAll("\\s+", " ").strip();
    }

    private static String unescape(String text) {
        return text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&nbsp;", " ");
    }
}
