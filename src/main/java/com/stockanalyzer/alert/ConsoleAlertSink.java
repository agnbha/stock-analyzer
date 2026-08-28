package com.stockanalyzer.alert;

import com.stockanalyzer.model.Alert;
import com.stockanalyzer.util.MarketClock;

/** Prints alerts inline with the live view. */
public final class ConsoleAlertSink implements AlertSink {

    private final MarketClock clock;

    public ConsoleAlertSink(MarketClock clock) {
        this.clock = clock;
    }

    @Override
    public void publish(Alert alert) {
        System.out.printf("%s  %-7s %-9s %s%n",
                clock.timeOf(alert.firedAtEpoch()).withNano(0),
                alert.severity(),
                alert.symbol() == null ? "-" : alert.symbol(),
                alert.message());
    }
}
