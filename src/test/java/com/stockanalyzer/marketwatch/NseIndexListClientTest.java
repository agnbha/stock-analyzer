package com.stockanalyzer.marketwatch;

import com.stockanalyzer.markets.MarketDataException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NseIndexListClientTest {

    @Test
    @DisplayName("the file is named after the index in lower case without spaces")
    void fileNames() {
        assertEquals("niftysmallcap250", NseIndexListClient.fileName("NIFTY SMALLCAP 250"));
        assertEquals("nifty50", NseIndexListClient.fileName(" Nifty 50 "));
        NseIndexListClient client = new NseIndexListClient(HttpClient.newHttpClient(),
                "https://nsearchives.nseindia.com/content/indices/ind_%slist.csv");
        assertEquals("https://nsearchives.nseindia.com/content/indices/ind_niftymidcap150list.csv",
                client.url("NIFTY MIDCAP 150"));
    }

    @Test
    @DisplayName("the Symbol column, found by name, with quoted commas in names, once each and in capitals")
    void readsTheSymbols() {
        String csv = "﻿Company Name,Industry,Symbol,Series,ISIN Code\r\n"
                + "Alpha Ltd.,Capital Goods,ALPHA,EQ,INE000A01011\r\n"
                + "\"Beta, Gamma & Co. Ltd.\",Services,BETA&CO,EQ,INE000B01011\r\n"
                + "Delta-Epsilon Ltd.,Chemicals, delta-eps ,EQ,INE000C01011\r\n"
                + "Alpha Ltd.,Capital Goods,ALPHA,EQ,INE000A01011\r\n"
                + "\r\n";

        assertEquals(List.of("ALPHA", "BETA&CO", "DELTA-EPS"), NseIndexListClient.parse(csv, "NIFTY TEST"));
    }

    @Test
    @DisplayName("a column moved does not shift the symbols")
    void columnFoundByName() {
        String csv = "Symbol,Company Name\nALPHA,Alpha Ltd.\nBETA,Beta Ltd.\n";
        assertEquals(List.of("ALPHA", "BETA"), NseIndexListClient.parse(csv, "NIFTY TEST"));
    }

    @Test
    @DisplayName("a file with no Symbol column, or no companies, is refused rather than emptying the index")
    void refusesWhatIsNotAList() {
        MarketDataException page = assertThrows(MarketDataException.class,
                () -> NseIndexListClient.parse("<html><body>Access Denied</body></html>", "NIFTY TEST"));
        assertTrue(page.getMessage().contains("no Symbol column"), page.getMessage());
        MarketDataException empty = assertThrows(MarketDataException.class,
                () -> NseIndexListClient.parse("Company Name,Industry,Symbol,Series,ISIN Code\n", "NIFTY TEST"));
        assertTrue(empty.getMessage().contains("names no companies"), empty.getMessage());
    }
}
