package com.stockanalyzer.markets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FRED's API answer, for the crude oil prices. The readings and the key are invented. */
class FredClientTest {

    @Test
    @DisplayName("a date and a value a reading, oldest first; a day without a reading ('.') is left out")
    void readings() {
        List<Observation> readings = FredClient.parse("DCOILBRENTEU", 200, """
                {"realtime_start": "2026-11-18", "observations": [
                  {"realtime_start": "2026-11-18", "date": "2026-11-09", "value": "90.51"},
                  {"realtime_start": "2026-11-18", "date": "2026-11-10", "value": "."},
                  {"realtime_start": "2026-11-18", "date": "2026-11-11", "value": "92.14"}]}""");

        assertEquals(List.of(new Observation(LocalDate.of(2026, 11, 9), 90.51),
                new Observation(LocalDate.of(2026, 11, 11), 92.14)), readings);
    }

    @Test
    @DisplayName("a refusal is reported in FRED's own words, and never with the key")
    void refusal() {
        MarketDataException refused = assertThrows(MarketDataException.class, () -> FredClient.parse("NOSUCH", 400,
                "{\"error_code\":400,\"error_message\":\"Bad Request.  The series does not exist.\"}"));
        assertTrue(refused.getMessage().contains("The series does not exist"), refused.getMessage());
        assertThrows(MarketDataException.class, () -> FredClient.parse("DCOILWTICO", 502, "<html>Bad gateway</html>"));
    }

    @Test
    @DisplayName("without a key nothing is asked, and the error says where to get one")
    void noKey() {
        FredClient client = new FredClient(HttpClient.newHttpClient(), "http://127.0.0.1:9", " ");
        MarketDataException missing = assertThrows(MarketDataException.class,
                () -> client.fetch("DCOILBRENTEU", LocalDate.of(2026, 11, 1)));
        assertTrue(missing.getMessage().contains("FRED_API_KEY"), missing.getMessage());
    }

    @Test
    @DisplayName("a failure to connect is reported without the address, which carries the key")
    void keyStaysOutOfErrors() {
        FredClient client = new FredClient(HttpClient.newHttpClient(), "http://127.0.0.1:9", "invented-key-1234");
        MarketDataException failed = assertThrows(MarketDataException.class,
                () -> client.fetch("DCOILBRENTEU", LocalDate.of(2026, 11, 1)));
        assertFalse(failed.getMessage().contains("invented-key-1234"), failed.getMessage());
        assertFalse(String.valueOf(failed.getCause()).contains("invented-key-1234"));
    }
}
