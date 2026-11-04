package com.stockanalyzer.store;

import com.stockanalyzer.model.Alert;

import java.time.LocalDate;
import java.util.List;

public interface AlertRepository {

    /** True when this exact alert has already been sent - the restart guard. */
    boolean alreadyFired(String idempotencyKey);

    void logFired(Alert alert);

    List<Alert> firedOn(LocalDate sessionDate);
}
