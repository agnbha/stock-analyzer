package com.stockanalyzer.model;

import java.time.LocalDate;

/**
 * A row of the day plan materialised at session start, so what will fire can be
 * inspected before it fires.
 */
public record ScheduledAlert(long id,
                             LocalDate sessionDate,
                             long fireAtEpoch,
                             String symbol,
                             String rule,
                             String payload,
                             Status status) {

    public enum Status { PENDING, FIRED, SKIPPED }
}
