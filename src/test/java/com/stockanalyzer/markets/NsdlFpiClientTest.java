package com.stockanalyzer.markets;

import com.stockanalyzer.markets.NsdlFpiClient.Day;
import com.stockanalyzer.markets.NsdlFpiClient.Measure;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NSDL's FPI archive, in its own layout. The figures are invented. */
class NsdlFpiClientTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** A reporting day as the archive writes it: the date and the conversion rate span the day's rows. */
    private static String day(String date, String equityExchange, String equityPrimary, String equity,
                              String debtGeneral, String debtFar, String total) {
        return "<tr><td rowspan='25'>" + date + "</td><td rowspan='3'>Equity</td><td>Stock Exchange</td>"
                + "<td align='right'>1,000.00</td><td align='right'>900.00</td><td align='right'>" + equityExchange
                + "</td><td align='right'>1.00</td><td rowspan='25'> Rs.90.0000</td></tr>"
                + "<tr><td>Primary market &amp; others</td><td align='right'>10.00</td><td align='right'>0.00</td>"
                + "<td align='right'>" + equityPrimary + "</td><td align='right'>0.10</td></tr>"
                + "<tr><td>Sub-total</td><td align='right'>1010.00</td><td align='right'>900.00</td>"
                + "<td align='right'>" + equity + "</td><td align='right'>1.10</td></tr>"
                + "<tr><td rowspan='3'>Debt-General Limit</td><td>Stock Exchange</td><td align='right'>5.00</td>"
                + "<td align='right'>0.00</td><td align='right'>5.00</td><td align='right'>0.05</td></tr>"
                + "<tr><td>Primary market &amp; others</td><td align='right'>0.00</td><td align='right'>0.00</td>"
                + "<td align='right'>0.00</td><td align='right'>0.00</td></tr>"
                + "<tr><td>Sub-total</td><td align='right'>5.00</td><td align='right'>0.00</td><td align='right'>"
                + debtGeneral + "</td><td align='right'>0.05</td></tr>"
                + "<tr><td rowspan='3'>Debt-FAR</td><td>Stock Exchange</td><td align='right'>0.00</td>"
                + "<td align='right'>0.00</td><td align='right'>0.00</td><td align='right'>0.00</td></tr>"
                + "<tr><td>Primary market &amp; others</td><td align='right'>0.00</td><td align='right'>0.00</td>"
                + "<td align='right'>0.00</td><td align='right'>0.00</td></tr>"
                + "<tr><td>Sub-total</td><td align='right'>0.00</td><td align='right'>0.00</td><td align='right'>"
                + debtFar + "</td><td align='right'>0.00</td></tr>"
                + "<tr><td rowspan='6'>Mutual Funds</td><td>Equity schemes</td><td align='right'>1.00</td>"
                + "<td align='right'>0.00</td><td align='right'>1.00</td><td align='right'>0.01</td></tr>"
                + "<tr><td>Sub-total</td><td align='right'>1.00</td><td align='right'>0.00</td><td align='right'>1.00"
                + "</td><td align='right'>0.01</td></tr>"
                + "<tr><td>Total</td><td align='right'>2,000.00</td><td align='right'>1,500.00</td><td align='right'>"
                + total + "</td><td align='right'>5.00</td></tr>";
    }

    private static String page(String... days) {
        return "<html><form method=\"post\" action=\"./Archive.aspx\" id=\"frmMonthly\">"
                + "<div id=\"dvArchiveData\" class=\"mT20\"><table cellspacing='0' class='tbls01'>"
                + "<tr><th colspan='8'>Daily Trends in FPI Investments up to 31-Oct-2026</th></tr>"
                + "<tr><th>Reporting <br/>Date</th><th>Debt/Equity</th><th>Investment Route</th>"
                + "<th>Gross Purchases(Rs Crore)</th><th>Gross Sales(Rs Crore)</th><th>Net Investment (Rs Crore)</th>"
                + "<th>Net Investment US($) million</th><th>Conversion</th></tr>"
                + String.join("", days)
                + "<tr><td rowspan='3'>Total for October</td><td>Equity</td><td align='right'>1.00</td>"
                + "<td align='right'>1.00</td><td align='right'>999.00</td><td align='right'>1.00</td></tr>"
                + "</table><table><tr><th>Daily Trends in FPI Derivative Trades on 31-Oct-2026</th></tr>"
                + "<tr><td rowspan='9'>30-Oct-2026</td><td>Index Futures</td><td>1.00</td><td>2.00</td><td>3.00</td>"
                + "<td>4.00</td><td>5.00</td><td>6.00</td></tr></table></div></form></html>";
    }

    @Test
    @DisplayName("a reporting day's net equity by route, its debt over every limit and the total; sales in brackets")
    void reportingDays() {
        SortedMap<LocalDate, Day> days = NsdlFpiClient.parse(page(
                day("29-Oct-2026", "(1,234.50)", "20.25", "(1,214.25)", "300.00", "(50.00)", "(965.25)"),
                day("30-Oct-2026", "800.00", "0.00", "800.00", "0.00", "0.00", "801.00")));

        assertEquals(List.of(LocalDate.of(2026, 10, 29), LocalDate.of(2026, 10, 30)), List.copyOf(days.keySet()),
                "the month's total and the derivatives table further down are not days");
        Day first = days.get(LocalDate.of(2026, 10, 29));
        assertEquals(-1214.25, first.get(Measure.EQUITY));
        assertEquals(-1234.50, first.get(Measure.EQUITY_EXCHANGE));
        assertEquals(20.25, first.get(Measure.EQUITY_PRIMARY));
        assertEquals(250.00, first.get(Measure.DEBT), "the general limit and FAR together");
        assertEquals(-965.25, first.get(Measure.TOTAL));
        assertEquals(800.00, days.get(LocalDate.of(2026, 10, 30)).get(Measure.EQUITY));
    }

    @Test
    @DisplayName("a page without the table has no reporting day yet; a day without its equity or total is refused")
    void noTableOrBrokenDay() {
        assertTrue(NsdlFpiClient.parse("<form id=\"frmMonthly\"><div id=\"dvArchiveData\"></div></form>").isEmpty());
        String noTotal = day("29-Oct-2026", "1.00", "0.00", "1.00", "0.00", "0.00", "1.00")
                .replaceAll("<tr><td>Total</td>.*$", "");
        assertThrows(MarketDataException.class, () -> NsdlFpiClient.parse(page(noTotal)));
    }

    @Test
    @DisplayName("the form's view state is read from the page and sent back with each date")
    void hiddenFields() {
        Map<String, String> fields = NsdlFpiClient.hiddenFields(
                "<input type=\"hidden\" name=\"__VIEWSTATE\" id=\"__VIEWSTATE\" value=\"abc+/=\" />"
                        + "<input type=\"hidden\" name=\"__VIEWSTATEGENERATOR\" id=\"g\" value=\"79DD0CB6\" />"
                        + "<input type=\"hidden\" name=\"__EVENTVALIDATION\" id=\"e\" value=\"x&amp;y\" />");
        assertEquals(Map.of("__VIEWSTATE", "abc+/=", "__VIEWSTATEGENERATOR", "79DD0CB6", "__EVENTVALIDATION", "x&y"),
                fields);
        assertThrows(MarketDataException.class, () -> NsdlFpiClient.hiddenFields("<html>maintenance</html>"));
    }

    @Test
    @DisplayName("a month is asked for once, up to its last day or today, whichever figure is wanted")
    void aMonthARequest() throws IOException {
        List<String> asked = new CopyOnWriteArrayList<>();
        serve(asked, Map.of(
                "31-Oct-2026", page(day("29-Oct-2026", "100.00", "0.00", "100.00", "0.00", "0.00", "100.00"),
                        day("30-Oct-2026", "200.00", "0.00", "200.00", "10.00", "0.00", "210.00")),
                "19-Nov-2026", page(day("18-Nov-2026", "(300.00)", "50.00", "(250.00)", "0.00", "0.00", "(250.00)"))));
        NsdlFpiClient client = client();

        assertEquals(List.of(new Observation(LocalDate.of(2026, 10, 30), 200.0),
                        new Observation(LocalDate.of(2026, 11, 18), -250.0)),
                client.fetch(Measure.EQUITY, LocalDate.of(2026, 10, 30)), "from the day asked for on");
        assertEquals(List.of(10.0, 0.0), client.fetch(Measure.DEBT, LocalDate.of(2026, 10, 30)).stream()
                .map(Observation::value).toList());
        assertEquals(List.of("GET", "POST 31-Oct-2026 abc", "POST 19-Nov-2026 abc"), asked,
                "the form once, then October to its end and November to today, each once");
    }

    @Test
    @DisplayName("a past month with no figures is an error; this month may have none yet")
    void emptyMonths() throws IOException {
        List<String> asked = new CopyOnWriteArrayList<>();
        serve(asked, Map.of("19-Nov-2026", "<form id=\"frmMonthly\"><div id=\"dvArchiveData\"></div></form>",
                "31-Oct-2026", "<form id=\"frmMonthly\"><div id=\"dvArchiveData\"></div></form>"));
        NsdlFpiClient client = client();

        assertEquals(List.of(), client.fetch(Measure.EQUITY, LocalDate.of(2026, 11, 1)));
        assertThrows(MarketDataException.class, () -> client.fetch(Measure.EQUITY, LocalDate.of(2026, 10, 1)));
        assertThrows(MarketDataException.class, () -> client.fetch(Measure.DEBT, LocalDate.of(2026, 10, 1)));
        assertEquals(3, asked.size(), "a month that failed is not asked for again in the same run");
    }

    private NsdlFpiClient client() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneId.of("UTC"));
        return new NsdlFpiClient(HttpClient.newHttpClient(),
                "http://localhost:" + server.getAddress().getPort() + "/web/Reports/Archive.aspx", clock, IST,
                Duration.ZERO);
    }

    private void serve(List<String> asked, Map<String, String> byDate) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/web/Reports/Archive.aspx", exchange -> {
            String body;
            assertEquals("stock-analyzer/1.0", exchange.getRequestHeaders().getFirst("User-Agent"));
            if (exchange.getRequestMethod().equals("GET")) {
                asked.add("GET");
                body = "<input type=\"hidden\" name=\"__VIEWSTATE\" id=\"__VIEWSTATE\" value=\"abc\" />";
            } else {
                Map<String, String> form = new java.util.HashMap<>();
                for (String pair : new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                        .split("&")) {
                    String[] kv = pair.split("=", 2);
                    form.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                            kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
                }
                asked.add("POST " + form.get("hdnDate") + " " + form.get("__VIEWSTATE"));
                assertEquals("btnSubmit1", form.get("__EVENTTARGET"));
                body = byDate.getOrDefault(form.get("hdnDate"), "<html>no such date</html>");
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }
}
