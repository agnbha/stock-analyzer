package com.stockanalyzer.markets;

import com.stockanalyzer.markets.NseParticipantOiClient.Participant;
import com.stockanalyzer.markets.NseParticipantOiClient.Side;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** NSE's participant-wise open interest files, in their own layout. The positions are invented. */
class NseParticipantOiClientTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static String file(long fiiLong, long fiiShort) {
        return "\"\"Participant wise Open Interest (no. of contracts) in Equity Derivatives as on Nov 13, 2026\"\",,,,\r\n"
                + "Client Type,Future Index Long,Future Index Short,Future Stock Long,Future Stock Short       ,"
                + "Total Long Contracts      ,Total Short Contracts\r\n"
                + "Client,1000,800,5,6,1005,806\r\n"
                + "DII,300,100,7,8,307,108\r\n"
                + "FII," + fiiLong + "," + fiiShort + ",9,10,0,0\r\n"
                + "Pro,200,600,11,12,211,612\r\n"
                + "TOTAL,1600,1600,32,36,1632,1636\r\n";
    }

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("each participant's index futures held long and short, the columns found by their names")
    void positions() {
        Map<Participant, NseParticipantOiClient.Position> day = NseParticipantOiClient.parse(file(400, 1200));
        assertEquals(new NseParticipantOiClient.Position(400, 1200), day.get(Participant.FII));
        assertEquals(new NseParticipantOiClient.Position(300, 100), day.get(Participant.DII));
        assertEquals(new NseParticipantOiClient.Position(1000, 800), day.get(Participant.CLIENT));
        assertEquals(new NseParticipantOiClient.Position(200, 600), day.get(Participant.PRO));
        assertThrows(MarketDataException.class, () -> NseParticipantOiClient.parse("<html>Not Found</html>"));
        assertThrows(MarketDataException.class,
                () -> NseParticipantOiClient.parse("title\nClient Type,Option Index Call Long\nFII,1\n"));
    }

    @Test
    @DisplayName("a file a weekday, read once for every series; a day without one (a holiday, today before it is out) "
            + "is skipped")
    void aFileAWeekday() throws IOException {
        List<String> asked = new CopyOnWriteArrayList<>();
        NseParticipantOiClient client = client(asked, Map.of("12112026", file(400, 1200), "13112026", file(500, 1000),
                "17112026", file(600, 900)), 365);

        LocalDate from = LocalDate.of(2026, 11, 12);
        assertEquals(List.of(new Observation(LocalDate.of(2026, 11, 12), 400),
                        new Observation(LocalDate.of(2026, 11, 13), 500), new Observation(LocalDate.of(2026, 11, 17), 600)),
                client.fetch(Participant.FII, Side.LONG, from));
        assertEquals(List.of(1200.0, 1000.0, 900.0),
                client.fetch(Participant.FII, Side.SHORT, from).stream().map(Observation::value).toList());
        assertEquals(List.of(300.0, 300.0, 300.0),
                client.fetch(Participant.DII, Side.LONG, from).stream().map(Observation::value).toList());
        assertEquals(List.of("12112026", "13112026", "16112026", "17112026", "18112026", "19112026"), asked,
                "weekdays only, each once: the 16th a holiday, the 18th and 19th not out yet");
    }

    @Test
    @DisplayName("a first read goes back no further than the history allowed, and a refusal is an error")
    void historyAndRefusal() throws IOException {
        List<String> asked = new CopyOnWriteArrayList<>();
        NseParticipantOiClient client = client(asked, Map.of(), 5);
        assertEquals(List.of(), client.fetch(Participant.FII, Side.LONG, LocalDate.of(2025, 1, 1)));
        assertEquals(List.of("16112026", "17112026", "18112026", "19112026"), asked,
                "five days back is Saturday the 14th: the weekend skipped, Monday to today");

        stop();
        NseParticipantOiClient refusing = client(new CopyOnWriteArrayList<>(), Map.of("19112026", "REFUSE"), 0);
        assertThrows(MarketDataException.class, () -> refusing.fetch(Participant.FII, Side.LONG,
                LocalDate.of(2026, 11, 19)));
    }

    private NseParticipantOiClient client(List<String> asked, Map<String, String> files, int historyDays)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/content/nsccl/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String date = path.replaceAll(".*fao_participant_oi_(\\d{8})\\.csv$", "$1");
            asked.add(date);
            String body = files.get(date);
            int status = body == null ? 404 : body.equals("REFUSE") ? 403 : 200;
            byte[] bytes = (body == null ? "<html>not found</html>" : body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        Clock clock = Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneId.of("UTC"));
        return new NseParticipantOiClient(HttpClient.newHttpClient(), "http://localhost:"
                + server.getAddress().getPort() + "/content/nsccl/fao_participant_oi_%s.csv", clock, IST, historyDays,
                Duration.ZERO);
    }
}
