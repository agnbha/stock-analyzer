package com.stockanalyzer.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How often, and how many a night, the company details of the tracked symbols and of every other one are read. */
class CompanyProfileSettingsTest {

    @Test
    @DisplayName("the symbols outside the tracked list are read again every three months, fifty pages a night")
    void shippedDefaults() {
        // An environment variable of the same name would outrank the file; a developer's shell has none.
        AppConfig config = AppConfig.load();

        assertEquals(90, config.companyProfileUntrackedRefreshDays());
        assertEquals(50, config.companyProfileUntrackedMaxPagesPerRun());
        assertEquals(1, config.companyProfileRefreshDays(), "the tracked symbols every evening, for their filings");
        assertEquals(0, config.companyProfileMaxPagesPerRun(), "all of them, however many are due");
    }

    @Test
    @DisplayName("every tracked company each evening fits the evening batch at the screener.in pace")
    void theTrackedRoundFitsTheEvening() {
        AppConfig config = AppConfig.load();

        long minutes = config.stockSymbols().size() * config.screenerRequestIntervalMillis() / 60_000;
        assertTrue(minutes <= 15, "the tracked round takes " + minutes + " minutes for "
                + config.stockSymbols().size() + " companies");
    }

    @Test
    @DisplayName("fifty a night, on the five weekday nights, turns about 2,800 other symbols over within the 90 days")
    void theBudgetCoversTheMarketInTime() {
        AppConfig config = AppConfig.load();

        long weekdayNights = config.companyProfileUntrackedRefreshDays() / 7 * 5;
        long pages = weekdayNights * config.companyProfileUntrackedMaxPagesPerRun();

        assertTrue(pages >= 2_800, "pages read in one refresh period: " + pages);
        long minutes = config.companyProfileUntrackedMaxPagesPerRun() * config.screenerRequestIntervalMillis() / 60_000;
        assertTrue(minutes <= 15, "the evening step takes " + minutes + " more minutes at the screener.in pace");
    }
}
