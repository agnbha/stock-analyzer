package com.stockanalyzer.canslim;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrowwInstrumentCatalogTest {

    /** The columns the catalog reads, plus one it ignores, in Groww's order. */
    private static final String CSV = """
            exchange,exchange_token,trading_symbol,groww_symbol,name,instrument_type,segment,series,isin,lot_size
            NSE,2885,RELIANCE,NSE-RELIANCE,Reliance Industries Limited,EQ,CASH,EQ,INE002A01018,1
            BSE,500325,RELIANCE,BSE-RELIANCE,Reliance Industries,EQ,CASH,A,INE002A01018,1
            NSE,3499,TATASTEEL,NSE-TATASTEEL,"Tata Steel Limited, The",EQ,CASH,EQ,INE081A01020,1
            BSE,500123,BSEONLY,BSE-BSEONLY,Bse Only Ltd,EQ,CASH,B,INE111B01010,1
            BSE,500999,TATASTEEL,BSE-TATASTEEL,A Different Tata Steel,EQ,CASH,B,INE999Z01011,1
            BSE,540001,SMEBSE,BSE-SMEBSE,Bse Sme Ltd,EQ,CASH,X,INE222C01012,1
            NSE,9001,SMENSE,NSE-SMENSE,Nse Sme Ltd,EQ,CASH,SM,INE333D01013,1
            NSE,9002,1015ECL29,NSE-1015ECL29,Ecl Finance Bond,EQ,CASH,NR,INE804IA7014,1
            NSE,9003,NIFTYBEES,NSE-NIFTYBEES,Nippon Nifty Bees,EQ,CASH,EQ,INF204KB14I2,1
            NSE,9004,RELIANCE26NOV1400CE,NSE-RELIANCE-CE,,CE,FNO,,,500
            NSE,9005,BZSTOCK,NSE-BZSTOCK,Trade For Trade Ltd,EQ,CASH,BZ,INE444E01014,1
            """;

    private static final GrowwInstrumentCatalog.Settings MAIN_BOARD = settings(Path.of("unused"),
            Set.of("EQ", "BE", "BZ"), Set.of("A", "B", "T", "Z", "P"));

    @TempDir
    Path directory;

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static GrowwInstrumentCatalog.Settings settings(Path cache, Set<String> nse, Set<String> bse) {
        return new GrowwInstrumentCatalog.Settings("http://localhost:1/instrument.csv", cache, Duration.ofHours(24),
                Set.of("NSE", "BSE"), nse, bse);
    }

    private static List<MarketListing> parse(GrowwInstrumentCatalog.Settings settings) throws IOException {
        return GrowwInstrumentCatalog.parse(new StringReader(CSV), settings);
    }

    @Test
    @DisplayName("only listed equity shares: no bonds, ETFs, derivatives or SME boards")
    void equitiesOnly() throws IOException {
        List<String> symbols = parse(MAIN_BOARD).stream().map(MarketListing::symbol).toList();

        assertEquals(List.of("BZSTOCK", "RELIANCE", "TATASTEEL", "BSEONLY"), symbols);
    }

    @Test
    @DisplayName("a company on both exchanges is taken once, from NSE")
    void nseWinsDualListings() throws IOException {
        List<MarketListing> reliance = parse(MAIN_BOARD).stream().filter(l -> l.isin().equals("INE002A01018")).toList();

        assertEquals(1, reliance.size());
        assertEquals("NSE", reliance.getFirst().exchange());
        assertEquals("RELIANCE", reliance.getFirst().screenerKey());
    }

    @Test
    @DisplayName("a BSE-only company is kept, addressed by its BSE scrip code on screener.in")
    void bseOnlyUsesTheScripCode() throws IOException {
        MarketListing bseOnly = parse(MAIN_BOARD).stream().filter(l -> l.symbol().equals("BSEONLY")).findFirst()
                .orElseThrow();

        assertEquals("BSE", bseOnly.exchange());
        assertEquals("500123", bseOnly.screenerKey());
        assertEquals("B", bseOnly.series());
    }

    @Test
    @DisplayName("a BSE company whose symbol is already a different NSE company's is skipped, not mixed up with it")
    void symbolCollisionIsSkipped() throws IOException {
        List<MarketListing> steel = parse(MAIN_BOARD).stream().filter(l -> l.symbol().equals("TATASTEEL")).toList();

        assertEquals(1, steel.size());
        assertEquals("INE081A01020", steel.getFirst().isin());
    }

    @Test
    @DisplayName("a comma inside a quoted name does not shift the columns")
    void quotedCommas() throws IOException {
        MarketListing steel = parse(MAIN_BOARD).stream().filter(l -> l.symbol().equals("TATASTEEL")).findFirst()
                .orElseThrow();

        assertEquals("Tata Steel Limited, The", steel.name());
        assertEquals("EQ", steel.series());
    }

    @Test
    @DisplayName("naming the SME series brings those boards in")
    void smeIsOptIn() throws IOException {
        List<String> symbols = parse(settings(Path.of("unused"), Set.of("EQ", "BE", "BZ", "SM"),
                Set.of("A", "B", "X"))).stream().map(MarketListing::symbol).toList();

        assertTrue(symbols.containsAll(List.of("SMENSE", "SMEBSE")), symbols.toString());
    }

    @Test
    @DisplayName("restricting the exchanges leaves the other one out")
    void exchangeFilter() throws IOException {
        List<MarketListing> nseOnly = GrowwInstrumentCatalog.parse(new StringReader(CSV),
                new GrowwInstrumentCatalog.Settings("u", Path.of("x"), Duration.ofHours(1), Set.of("NSE"),
                        Set.of("EQ", "BZ"), Set.of("A", "B")));

        assertTrue(nseOnly.stream().allMatch(l -> l.exchange().equals("NSE")));
    }

    @Test
    @DisplayName("a changed format is reported by name, not as an empty market")
    void missingColumn() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> GrowwInstrumentCatalog.parse(
                new StringReader("exchange,trading_symbol\nNSE,X\n"), MAIN_BOARD));

        assertTrue(failure.getMessage().contains("isin"), failure.getMessage());
    }

    private AtomicInteger serve(int status) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/instrument.csv", exchange -> {
            requests.incrementAndGet();
            byte[] body = status == 200 ? CSV.getBytes(StandardCharsets.UTF_8) : new byte[0];
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        return requests;
    }

    private GrowwInstrumentCatalog catalog(Path cache) {
        return new GrowwInstrumentCatalog(HttpClient.newHttpClient(), new GrowwInstrumentCatalog.Settings(
                "http://localhost:" + server.getAddress().getPort() + "/instrument.csv", cache,
                Duration.ofHours(24), Set.of("NSE", "BSE"), Set.of("EQ", "BE", "BZ"), Set.of("A", "B")));
    }

    @Test
    @DisplayName("the file is downloaded once and reused while it is fresh")
    void cachesTheDownload() throws IOException {
        AtomicInteger requests = serve(200);
        Path cache = directory.resolve("data/instruments.csv");

        catalog(cache).listings();
        List<MarketListing> again = catalog(cache).listings();

        assertEquals(1, requests.get());
        assertTrue(Files.exists(cache));
        assertEquals(4, again.size());
    }

    @Test
    @DisplayName("a failed refresh falls back to the copy on disk")
    void staleCopySurvivesAFailedRefresh() throws IOException {
        AtomicInteger requests = serve(500);
        Path cache = directory.resolve("instruments.csv");
        Files.writeString(cache, CSV);
        assertTrue(cache.toFile().setLastModified(System.currentTimeMillis() - Duration.ofDays(3).toMillis()));

        List<MarketListing> listings = catalog(cache).listings();

        assertEquals(1, requests.get(), "it did try");
        assertEquals(4, listings.size());
    }

    @Test
    @DisplayName("no copy and no download is an error that says where it looked")
    void nothingToUse() throws IOException {
        serve(500);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> catalog(directory.resolve("none.csv")).listings());

        assertTrue(failure.getMessage().contains("instrument.csv"), failure.getMessage());
    }
}
