package com.stockanalyzer.alert;

import com.stockanalyzer.model.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Fans one alert out to every configured channel; a failing channel never blocks the others. */
public final class CompositeAlertSink implements AlertSink {

    private static final Logger log = LoggerFactory.getLogger(CompositeAlertSink.class);

    private final List<AlertSink> sinks;

    public CompositeAlertSink(List<AlertSink> sinks) {
        this.sinks = List.copyOf(sinks);
    }

    @Override
    public void publish(Alert alert) {
        for (AlertSink sink : sinks) {
            try {
                sink.publish(alert);
            } catch (Exception e) {
                log.warn("Alert sink {} failed: {}", sink.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
}
