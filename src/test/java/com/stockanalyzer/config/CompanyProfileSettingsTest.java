package com.stockanalyzer.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How often, and how many a night, the company details of the symbols outside symbols.txt are read. */
class CompanyProfileSettingsTest {

    @Test
    @DisplayName("the symbols outside the tracked list are read again every three months, fifty pages a night")
    void shippedDefaults() {
        // An environment variable of the same name would outrank the file; a developer's shell has none.
        AppConfig config = AppConfig.load();

        assertEquals(90, config.companyProfileUntrackedRefreshDays());
        assertEquals(50, config.companyProfileUntrackedMaxPagesPerRun());
        assertEquals(7, config.companyProfileRefreshDays(), "the tracked symbols keep their weekly round");
        assertEquals(12, config.companyProfileMaxPagesPerRun());
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
