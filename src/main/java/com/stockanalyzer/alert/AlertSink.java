package com.stockanalyzer.alert;

import com.stockanalyzer.model.Alert;

/** Where an alert goes. Adding a channel is a new implementation, nothing else. */
public interface AlertSink {

    void publish(Alert alert);
}
